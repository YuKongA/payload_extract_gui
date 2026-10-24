use payload_extract::payload::PayloadView;

/// Recognize the descriptor path returned by Android's FilePicker. Other inputs
/// stay on the extractor's normal local-file / HTTP path.
pub(crate) fn open(input: &str) -> Option<anyhow::Result<PayloadView>> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if let Some(fd) = input.strip_prefix("/proc/self/fd/") {
        use anyhow::Context;

        return Some((|| {
            let fd: i32 = fd.parse().context("Invalid document file descriptor")?;
            anyhow::ensure!(fd >= 0, "Invalid document file descriptor");
            // SAF owns the descriptor; map directly to avoid path permission checks.
            // The source must remain unchanged while mapped.
            let mmap = unsafe { memmap2::Mmap::map(fd) }
                .context("Failed to mmap document file descriptor")?;
            if mmap.starts_with(b"PK\x03\x04") {
                payload_extract::input::zip_input::open_zip(mmap)
                    .context("Failed to open ZIP payload")
            } else {
                payload_extract::input::local::open_local(mmap)
                    .context("Failed to open raw payload")
            }
        })());
    }

    let _ = input;
    None
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use std::io::{Seek, SeekFrom, Write};
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn reads_granted_descriptor_without_path_access_or_taking_ownership() {
        let mut file = tempfile::tempfile().unwrap();
        // One four-byte system partition; blob bytes follow the manifest.
        let manifest = b"\x18\x04\x6a\x0c\x0a\x06system\x3a\x02\x08\x04";
        file.write_all(b"CrAU").unwrap();
        file.write_all(&2u64.to_be_bytes()).unwrap();
        file.write_all(&(manifest.len() as u64).to_be_bytes())
            .unwrap();
        file.write_all(&0u32.to_be_bytes()).unwrap();
        file.write_all(manifest).unwrap();
        file.write_all(b"test").unwrap();
        file.seek(SeekFrom::Start(7)).unwrap();
        file.set_permissions(std::fs::Permissions::from_mode(0))
            .unwrap();

        let path = format!("/proc/self/fd/{}", file.as_raw_fd());
        let payload = super::open(&path).unwrap().unwrap();
        assert_eq!(payload.partitions()[0].partition_name, "system");
        assert_eq!(payload.block_size(), 4);
        assert_eq!(payload.blob_slice_raw(0, 4).unwrap(), b"test");
        assert_eq!(file.stream_position().unwrap(), 7);
        // The Java-owned descriptor is still valid; the mapping also survives it.
        file.metadata().unwrap();
        drop(file);
        assert_eq!(payload.blob_slice_raw(0, 4).unwrap(), b"test");
    }

    #[test]
    fn rejects_invalid_descriptors_without_owning_them() {
        assert!(super::open("/proc/self/fd/-1").unwrap().is_err());
        assert!(super::open("/proc/self/fd/not-a-number").unwrap().is_err());
    }
}
