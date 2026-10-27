use std::fs::File;
use std::io;

/// Duplicate a borrowed Java descriptor without reopening any filesystem path.
/// `fcntl` reports invalid/closed descriptors and sets close-on-exec atomically.
#[cfg(unix)]
pub(crate) fn duplicate(fd: i32) -> io::Result<File> {
    use std::os::fd::FromRawFd;

    // SAF keeps the original open for the entire synchronous JNI call.
    let owned_fd = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if owned_fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAF's descriptor is never passed to from_raw_fd: only our fresh duplicate.
    Ok(unsafe { File::from_raw_fd(owned_fd) })
}

#[cfg(not(unix))]
pub(crate) fn duplicate(_fd: i32) -> io::Result<File> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "Document descriptor output is only supported on Unix",
    ))
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::io::{Read, Seek, SeekFrom, Write};
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::{FileExt, PermissionsExt};

    use payload_extract::extract::{ExtractConfig, extract_partition_to_file, extract_partitions};
    use payload_extract::payload::PayloadView;

    // Small protobuf fixture: three four-byte blocks, with two REPLACEs around
    // a ZERO block, followed by an unwritten block. Metadata requests 16 bytes.
    // Encode only these fixed, single-byte protobuf lengths, without a second
    // protobuf dependency in the JNI crate.
    fn payload(corrupt_hash: bool) -> PayloadView {
        let mut hash =
            hex::decode("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
                .unwrap();
        if corrupt_hash {
            hash[0] ^= 1;
        }
        let mut replace = vec![0x08, 0, 0x10, 0, 0x18, 4, 0x32, 4, 0x08, 2, 0x10, 1];
        replace.extend_from_slice(&[0x42, 32]);
        replace.extend_from_slice(&hash);
        let replace_first = [0x08, 0, 0x10, 4, 0x18, 4, 0x32, 4, 0x08, 0, 0x10, 1];
        let zero = [0x08, 6, 0x32, 4, 0x08, 1, 0x10, 1];
        let mut partition = b"\x0a\x06system\x3a\x02\x08\x10".to_vec();
        for op in [&replace[..], &replace_first[..], &zero[..]] {
            partition.extend_from_slice(&[0x42, op.len() as u8]);
            partition.extend_from_slice(op);
        }
        let mut manifest = vec![0x18, 4, 0x6a, partition.len() as u8];
        manifest.extend_from_slice(&partition);
        let mut input = tempfile::tempfile().unwrap();
        input.write_all(b"CrAU").unwrap();
        input.write_all(&2u64.to_be_bytes()).unwrap();
        input
            .write_all(&(manifest.len() as u64).to_be_bytes())
            .unwrap();
        input.write_all(&0u32.to_be_bytes()).unwrap();
        input.write_all(&manifest).unwrap();
        input.write_all(b"testdata").unwrap();
        PayloadView::from_mmap(unsafe { memmap2::Mmap::map(&input) }.unwrap(), 0).unwrap()
    }

    fn config(verify: bool) -> ExtractConfig {
        ExtractConfig {
            verify_ops: verify,
            threads: 2,
            quiet: true,
            source_dir: None,
            out_config: None,
            progress: None,
        }
    }

    #[test]
    fn extracts_granted_fd_after_path_access_is_revoked_and_preserves_owner() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("document.img");
        let mut original = File::options()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&path)
            .unwrap();
        original.write_all(&[0xff; 64]).unwrap();
        original.seek(SeekFrom::Start(7)).unwrap();
        original
            .set_permissions(std::fs::Permissions::from_mode(0o000))
            .unwrap();
        // Revoke the original pathname even when tests run as root. An open
        // document grant is still usable, just as with a SAF-owned descriptor.
        std::fs::remove_file(&path).unwrap();
        assert!(File::open(&path).is_err());
        let output = duplicate(original.as_raw_fd()).unwrap();
        extract_partition_to_file(&payload(false), &output, "system", &config(true)).unwrap();
        drop(output);

        assert_eq!(original.metadata().unwrap().len(), 16);
        assert_eq!(original.stream_position().unwrap(), 7);
        let mut bytes = [0xff; 16];
        original.read_exact_at(&mut bytes, 0).unwrap();
        assert_eq!(&bytes, b"data\0\0\0\0test\0\0\0\0");
        // Neither the JNI duplicate nor PartitionWriter consumed the owner.
        original.write_all_at(b"live", 0).unwrap();
    }

    #[test]
    fn file_and_path_routes_share_operation_verification_and_exact_output() {
        let output = tempfile::tempfile().unwrap();
        let directory = tempfile::tempdir().unwrap();
        let names = ["system".to_owned()];
        let invalid = payload(true);
        assert!(extract_partition_to_file(&invalid, &output, "system", &config(true)).is_err());
        assert!(extract_partitions(&invalid, directory.path(), &names, &config(true)).is_err());
        // Verification is optional, but writer semantics must stay identical.
        extract_partition_to_file(&invalid, &output, "system", &config(false)).unwrap();
        extract_partitions(&invalid, directory.path(), &names, &config(false)).unwrap();
        let mut bytes = Vec::new();
        (&output).read_to_end(&mut bytes).unwrap();
        assert_eq!(bytes, b"data\0\0\0\0test\0\0\0\0");
        assert_eq!(
            std::fs::read(directory.path().join("system.img")).unwrap(),
            bytes
        );
    }

    #[test]
    fn duplicate_lifetime_is_independent_in_both_directions() {
        let original = tempfile::tempfile().unwrap();
        let output = duplicate(original.as_raw_fd()).unwrap();
        drop(original);
        output.write_all_at(b"still open", 0).unwrap();
        assert_eq!(output.metadata().unwrap().len(), 10);
        assert!(duplicate(-1).is_err());
    }

    #[test]
    fn extraction_error_does_not_close_the_borrowed_descriptor() {
        let original = tempfile::tempfile().unwrap();
        {
            let output = duplicate(original.as_raw_fd()).unwrap();
            assert!(
                extract_partition_to_file(&payload(false), &output, "missing", &config(true))
                    .is_err()
            );
        }
        original.write_all_at(b"owned by Java", 0).unwrap();
        assert_eq!(original.metadata().unwrap().len(), 13);
    }
}
