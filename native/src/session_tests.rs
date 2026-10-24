use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::FileExt;
use std::sync::{Arc, Barrier};

use super::{ExtractionOutput, document_output, extract_partition, session};

/// One four-byte REPLACE operation with a real SHA-256 operation hash. Keeping
/// the fixture encoded here avoids a second protobuf implementation/dependency.
fn write_payload(file: &mut File, corrupt_hash: bool) {
    let mut hash =
        hex::decode("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08").unwrap();
    if corrupt_hash {
        hash[0] ^= 1;
    }
    let mut operation = vec![0x08, 0, 0x10, 0, 0x18, 4, 0x32, 4, 0x08, 0, 0x10, 1];
    operation.extend_from_slice(&[0x42, 32]);
    operation.extend_from_slice(&hash);
    let mut partition = b"\x0a\x06system\x3a\x02\x08\x04".to_vec();
    partition.extend_from_slice(&[0x42, operation.len() as u8]);
    partition.extend_from_slice(&operation);
    let mut manifest = vec![0x18, 4, 0x6a, partition.len() as u8];
    manifest.extend_from_slice(&partition);
    file.write_all(b"CrAU").unwrap();
    file.write_all(&2u64.to_be_bytes()).unwrap();
    file.write_all(&(manifest.len() as u64).to_be_bytes())
        .unwrap();
    file.write_all(&0u32.to_be_bytes()).unwrap();
    file.write_all(&manifest).unwrap();
    file.write_all(b"test").unwrap();
}

#[test]
fn retained_local_session_reuses_view_after_original_close_and_unlink() {
    let directory = tempfile::tempdir().unwrap();
    let input_path = directory.path().join("payload.bin");
    let mut input = File::create(&input_path).unwrap();
    write_payload(&mut input, false);
    let original = session::open(input_path.to_str().unwrap().to_owned()).unwrap();
    let loaded = session::get(original).unwrap();
    let weak = Arc::downgrade(&loaded);
    let retained = session::retain(original).unwrap();
    drop(loaded);
    session::close(original).unwrap();
    drop(input);
    std::fs::remove_file(&input_path).unwrap();
    assert!(File::open(&input_path).is_err());
    assert!(session::get(original).is_err());

    let after_unlink = session::retain(retained).unwrap();
    session::close(retained).unwrap();
    let retained = after_unlink;
    let loaded = session::get(retained).unwrap();
    assert_eq!(loaded.payload.partitions()[0].partition_name, "system");
    let unused_cache = directory.path().join("unused-cache");
    let output = directory.path().join("output");
    extract_partition(
        &loaded,
        &unused_cache,
        "system",
        2,
        true,
        1001,
        ExtractionOutput::Directory(&output),
    )
    .unwrap();
    assert_eq!(std::fs::read(output.join("system.img")).unwrap(), b"test");
    assert!(!unused_cache.exists());
    session::close(retained).unwrap();
    assert!(weak.upgrade().is_some());
    drop(loaded);
    assert!(weak.upgrade().is_none());
}

#[test]
fn retained_document_session_survives_input_descriptor_close_and_preserves_output_owner() {
    let mut input = tempfile::tempfile().unwrap();
    write_payload(&mut input, false);
    input.seek(SeekFrom::Start(7)).unwrap();
    let input_path = format!("/proc/self/fd/{}", input.as_raw_fd());
    let original = session::open(input_path.clone()).unwrap();
    assert_eq!(input.stream_position().unwrap(), 7);
    let retained = session::retain(original).unwrap();
    session::close(original).unwrap();
    drop(input);

    let after_descriptor_close = session::retain(retained).unwrap();
    session::close(retained).unwrap();
    let retained = after_descriptor_close;
    let loaded = session::get(retained).unwrap();
    let directory = tempfile::tempdir().unwrap();
    // A file is deliberately not a valid cache directory. Local extraction
    // must neither inspect nor create a download cache, even for FD output.
    let not_a_cache = directory.path().join("not-a-cache");
    File::create(&not_a_cache).unwrap();
    let mut owner = tempfile::tempfile().unwrap();
    owner.write_all(&[0xff; 32]).unwrap();
    owner.seek(SeekFrom::Start(7)).unwrap();
    let duplicate = document_output::duplicate(owner.as_raw_fd()).unwrap();
    extract_partition(
        &loaded,
        &not_a_cache,
        "system",
        2,
        true,
        1002,
        ExtractionOutput::File(&duplicate),
    )
    .unwrap();
    drop(duplicate);
    assert_eq!(owner.metadata().unwrap().len(), 4);
    assert_eq!(owner.stream_position().unwrap(), 7);
    let mut bytes = [0; 4];
    owner.read_exact_at(&mut bytes, 0).unwrap();
    assert_eq!(&bytes, b"test");
    owner.write_all_at(b"live", 0).unwrap();
    session::close(retained).unwrap();
}

#[test]
fn loaded_local_extraction_preserves_operation_hash_verification() {
    let directory = tempfile::tempdir().unwrap();
    let input_path = directory.path().join("payload.bin");
    let mut input = File::create(&input_path).unwrap();
    write_payload(&mut input, true);
    let handle = session::open(input_path.to_str().unwrap().to_owned()).unwrap();
    let loaded = session::get(handle).unwrap();
    session::close(handle).unwrap();
    drop(input);
    std::fs::remove_file(input_path).unwrap();
    let mut output = tempfile::tempfile().unwrap();
    let unused_cache = directory.path().join("unused-cache");
    let result = extract_partition(
        &loaded,
        &unused_cache,
        "system",
        2,
        true,
        1003,
        ExtractionOutput::File(&output),
    );
    assert!(result.is_err());
    extract_partition(
        &loaded,
        &unused_cache,
        "system",
        2,
        false,
        1004,
        ExtractionOutput::File(&output),
    )
    .unwrap();
    let mut bytes = Vec::new();
    output.read_to_end(&mut bytes).unwrap();
    assert_eq!(bytes, b"test");
    assert!(!unused_cache.exists());
}

#[test]
fn in_flight_session_survives_concurrent_close_until_extraction_finishes() {
    let directory = tempfile::tempdir().unwrap();
    let input_path = directory.path().join("payload.bin");
    let mut input = File::create(&input_path).unwrap();
    write_payload(&mut input, false);
    let handle = session::open(input_path.to_str().unwrap().to_owned()).unwrap();
    let loaded = session::get(handle).unwrap();
    let weak = Arc::downgrade(&loaded);
    let ready = Arc::new(Barrier::new(2));
    let proceed = Arc::new(Barrier::new(2));
    let worker_ready = ready.clone();
    let worker_proceed = proceed.clone();
    let worker = std::thread::spawn(move || {
        worker_ready.wait();
        worker_proceed.wait();
        let mut output = tempfile::tempfile().unwrap();
        extract_partition(
            &loaded,
            std::path::Path::new("unused-download-cache"),
            "system",
            2,
            true,
            1005,
            ExtractionOutput::File(&output),
        )
        .unwrap();
        let mut bytes = Vec::new();
        output.read_to_end(&mut bytes).unwrap();
        bytes
    });
    ready.wait();
    session::close(handle).unwrap();
    assert!(session::get(handle).is_err());
    assert!(session::retain(handle).is_err());
    assert!(session::close(handle).is_err());
    drop(input);
    std::fs::remove_file(input_path).unwrap();
    assert!(weak.upgrade().is_some());
    proceed.wait();
    assert_eq!(worker.join().unwrap(), b"test");
    assert!(weak.upgrade().is_none());
}

#[test]
fn invalid_handles_are_rejected_without_creating_or_reusing_sessions() {
    for handle in [0, -1, i64::MIN, i64::MAX] {
        assert!(session::get(handle).is_err());
        assert!(session::retain(handle).is_err());
        assert!(session::close(handle).is_err());
    }
    let directory = tempfile::tempdir().unwrap();
    let input_path = directory.path().join("payload.bin");
    let mut input = File::create(&input_path).unwrap();
    write_payload(&mut input, false);
    let input_path = input_path.to_str().unwrap().to_owned();
    let first = session::open(input_path.clone()).unwrap();
    session::close(first).unwrap();
    let second = session::open(input_path).unwrap();
    assert!(session::get(first).is_err());
    session::close(second).unwrap();
}
