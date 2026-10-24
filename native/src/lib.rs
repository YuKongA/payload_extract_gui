mod document_input;
mod document_output;
mod session;

#[cfg(all(test, target_os = "linux"))]
mod session_tests;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

use anyhow::Context;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JString};
use jni::strings::JNIString;
use jni::sys::{jint, jlong, jstring};
use jni::{Env, EnvUnowned, jni_str};
use serde::Serialize;

use payload_extract::extract::{self, ExtractConfig};
use payload_extract::input::{self, OpenOptions, ProgressCallback};

/// Helper: throw a Java exception and return null.
fn throw_and_return_null(env: &mut Env, msg: &str) -> jstring {
    let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), JNIString::from(msg));
    ptr::null_mut()
}

/// Helper: get a Rust string from a JString.
fn get_string(env: &mut Env, s: &JString) -> Result<String, String> {
    s.try_to_string(env)
        .map_err(|e| format!("Failed to get string: {e}"))
}

// ============================================================
// Extraction progress registry
//
// `extractPartition` publishes two-phase progress into a token-keyed cell that
// the Kotlin side polls via `getExtractProgress`. The progress callbacks only
// touch atomics (no JNI calls), so the rayon/async worker threads that invoke
// them never need to attach to the JVM.
// ============================================================

const PHASE_DOWNLOAD: u8 = 0;
const PHASE_EXTRACT: u8 = 1;

struct ProgressCell {
    phase: AtomicU8,
    current: AtomicU64,
    total: AtomicU64,
}

static EXTRACT_PROGRESS: LazyLock<Mutex<HashMap<jlong, Arc<ProgressCell>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Removes the progress entry on drop, cleaning up the token on every exit path.
struct ProgressGuard(jlong);

impl Drop for ProgressGuard {
    fn drop(&mut self) {
        if let Ok(mut map) = EXTRACT_PROGRESS.lock() {
            map.remove(&self.0);
        }
    }
}

/// A `ProgressCallback` that records `(current, total)` for `phase` into `cell`.
fn progress_sink(cell: Arc<ProgressCell>, phase: u8) -> ProgressCallback {
    Arc::new(move |current, total| {
        cell.phase.store(phase, Ordering::Relaxed);
        cell.total.store(total, Ordering::Relaxed);
        cell.current.store(current, Ordering::Relaxed);
    })
}
enum ExtractionOutput<'a> {
    Directory(&'a Path),
    File(&'a std::fs::File),
}

fn extract_partition(
    session: &session::Session,
    temp_dir: &Path,
    part_name: &str,
    threads: jint,
    verify: bool,
    token: jlong,
    output: ExtractionOutput<'_>,
) -> anyhow::Result<()> {
    let is_http = session.input.starts_with("http://") || session.input.starts_with("https://");
    let cell = Arc::new(ProgressCell {
        phase: AtomicU8::new(if is_http {
            PHASE_DOWNLOAD
        } else {
            PHASE_EXTRACT
        }),
        current: AtomicU64::new(0),
        total: AtomicU64::new(0),
    });
    if let Ok(mut map) = EXTRACT_PROGRESS.lock() {
        map.insert(token, cell.clone());
    }
    let _guard = ProgressGuard(token);

    let partition_names = [part_name.to_owned()];
    // HTTP metadata sessions lack operation blobs; local sessions are complete.
    let downloaded;
    let payload = if is_http {
        std::fs::create_dir_all(temp_dir).context("Failed to create download cache directory")?;
        let open_opts = OpenOptions {
            insecure: false,
            user_agent: None,
            download_progress: Some(progress_sink(cell.clone(), PHASE_DOWNLOAD)),
            temp_dir: Some(PathBuf::from(temp_dir)),
        };
        downloaded = input::open_for_extract_with(&session.input, &partition_names, &open_opts)
            .context("Failed to open payload for extraction")?;
        &downloaded
    } else {
        &session.payload
    };

    cell.phase.store(PHASE_EXTRACT, Ordering::Relaxed);
    cell.current.store(0, Ordering::Relaxed);
    let config = ExtractConfig {
        verify_ops: verify,
        threads: threads as usize,
        quiet: true,
        source_dir: None,
        out_config: None,
        progress: Some(progress_sink(cell, PHASE_EXTRACT)),
    };
    match output {
        ExtractionOutput::Directory(path) => {
            extract::extract_partitions(payload, path, &partition_names, &config)
        }
        ExtractionOutput::File(file) => {
            extract::extract_partition_to_file(payload, file, part_name, &config)
        }
    }
    .context("Extraction failed")
}

// ============================================================
// JNI exports
// ============================================================

/// Open a payload file (local .bin, .zip, or URL) and return an opaque handle.
/// This opens for metadata only (header + manifest). For HTTP URLs, blob data
/// is NOT downloaded — use extractPartition which re-opens with open_for_extract.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_open(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    input_path: JString,
) -> jlong {
    unowned_env
        .with_env(|env| -> Result<jlong, jni::errors::Error> {
            let input_str = match get_string(env, &input_path) {
                Ok(s) => s,
                Err(e) => {
                    let _ = env.throw_new(
                        jni_str!("java/lang/RuntimeException"),
                        JNIString::from(e.as_str()),
                    );
                    return Ok(0);
                }
            };

            match session::open(input_str) {
                Ok(handle) => Ok(handle),
                Err(e) => {
                    let _ = env.throw_new(
                        jni_str!("java/lang/RuntimeException"),
                        JNIString::from(format!("{e:#}")),
                    );
                    Ok(0)
                }
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// List partitions as a JSON string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_listPartitionsJson(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    with_hash: bool,
) -> jstring {
    unowned_env
        .with_env(|env| -> Result<jstring, jni::errors::Error> {
            let session = match session::get(handle) {
                Ok(session) => session,
                Err(e) => return Ok(throw_and_return_null(env, &e.to_string())),
            };
            let payload = &session.payload;

            #[derive(Serialize)]
            struct PartitionEntry {
                name: String,
                size: u64,
                operations: usize,
                #[serde(skip_serializing_if = "Option::is_none")]
                hash: Option<String>,
            }

            let entries: Vec<PartitionEntry> = payload
                .partitions()
                .iter()
                .map(|p| {
                    let info = p.new_partition_info.as_ref();
                    let size = info.and_then(|i| i.size).unwrap_or(0);
                    let hash = if with_hash {
                        info.and_then(|i| i.hash.as_ref()).map(hex::encode)
                    } else {
                        None
                    };
                    PartitionEntry {
                        name: p.partition_name.clone(),
                        size,
                        operations: p.operations.len(),
                        hash,
                    }
                })
                .collect();

            let json = match serde_json::to_string(&entries) {
                Ok(s) => s,
                Err(e) => {
                    return Ok(throw_and_return_null(
                        env,
                        &format!("JSON serialization failed: {e}"),
                    ));
                }
            };

            match JString::from_str(env, &json) {
                Ok(s) => Ok(s.into_raw()),
                Err(e) => Ok(throw_and_return_null(
                    env,
                    &format!("Failed to create Java string: {e}"),
                )),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Get payload metadata as a JSON string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_getMetadataJson(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    unowned_env
        .with_env(|env| -> Result<jstring, jni::errors::Error> {
            let session = match session::get(handle) {
                Ok(session) => session,
                Err(e) => return Ok(throw_and_return_null(env, &e.to_string())),
            };
            let payload = &session.payload;
            let header = payload.header();
            let manifest = payload.manifest();

            #[derive(Serialize)]
            struct PayloadMetadata {
                version: u64,
                manifest_size: u64,
                metadata_signature_size: u32,
                block_size: u32,
                partition_count: usize,
                #[serde(skip_serializing_if = "Option::is_none")]
                max_timestamp: Option<i64>,
                #[serde(skip_serializing_if = "Option::is_none")]
                partial_update: Option<bool>,
                #[serde(skip_serializing_if = "Option::is_none")]
                security_patch_level: Option<String>,
            }

            let metadata = PayloadMetadata {
                version: header.version,
                manifest_size: header.manifest_size,
                metadata_signature_size: header.metadata_signature_size,
                block_size: payload.block_size(),
                partition_count: manifest.partitions.len(),
                max_timestamp: manifest.max_timestamp,
                partial_update: manifest.partial_update,
                security_patch_level: manifest.security_patch_level.clone(),
            };

            let json = match serde_json::to_string(&metadata) {
                Ok(s) => s,
                Err(e) => {
                    return Ok(throw_and_return_null(
                        env,
                        &format!("JSON serialization failed: {e}"),
                    ));
                }
            };

            match JString::from_str(env, &json) {
                Ok(s) => Ok(s.into_raw()),
                Err(e) => Ok(throw_and_return_null(
                    env,
                    &format!("Failed to create Java string: {e}"),
                )),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Extract a single partition, publishing two-phase progress under `token`.
///
/// Local input uses the session's parsed, mapped payload. HTTP streams the
/// needed blob into a temp file in the output dir and reports download progress;
/// then both input types report per-operation extraction progress.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_extractPartition(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    output_dir: JString,
    partition_name: JString,
    threads: jint,
    verify: bool,
    token: jlong,
) {
    unowned_env
        .with_env(|env| -> Result<(), jni::errors::Error> {
            let session = match session::get(handle) {
                Ok(session) => session,
                Err(e) => {
                    let _ = env.throw_new(
                        jni_str!("java/lang/RuntimeException"),
                        JNIString::from(e.to_string()),
                    );
                    return Ok(());
                }
            };

            let output_str = match get_string(env, &output_dir) {
                Ok(s) => s,
                Err(e) => {
                    let _ = env.throw_new(
                        jni_str!("java/lang/RuntimeException"),
                        JNIString::from(e.as_str()),
                    );
                    return Ok(());
                }
            };

            let part_name = match get_string(env, &partition_name) {
                Ok(s) => s,
                Err(e) => {
                    let _ = env.throw_new(
                        jni_str!("java/lang/RuntimeException"),
                        JNIString::from(e.as_str()),
                    );
                    return Ok(());
                }
            };

            if let Err(e) = extract_partition(
                &session,
                Path::new(&output_str),
                &part_name,
                threads,
                verify,
                token,
                ExtractionOutput::Directory(Path::new(&output_str)),
            ) {
                let _ = env.throw_new(
                    jni_str!("java/lang/RuntimeException"),
                    JNIString::from(format!("{e:#}")),
                );
            }

            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

/// Extract directly into a Java-owned writable document descriptor. Java must
/// keep it open for this synchronous call; Rust owns and closes only duplicates.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_extractPartitionToFd(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    output_fd: jint,
    temp_dir: JString,
    partition_name: JString,
    threads: jint,
    verify: bool,
    token: jlong,
) {
    unowned_env
        .with_env(|env| -> Result<(), jni::errors::Error> {
            let result = (|| -> anyhow::Result<()> {
                let session = session::get(handle)?;
                let temp_str = get_string(env, &temp_dir).map_err(anyhow::Error::msg)?;
                let part_name = get_string(env, &partition_name).map_err(anyhow::Error::msg)?;
                let file = document_output::duplicate(output_fd)
                    .context("Failed to duplicate output document descriptor")?;
                extract_partition(
                    &session,
                    Path::new(&temp_str),
                    &part_name,
                    threads,
                    verify,
                    token,
                    ExtractionOutput::File(&file),
                )
            })();
            if let Err(e) = result {
                let _ = env.throw_new(
                    jni_str!("java/lang/RuntimeException"),
                    JNIString::from(format!("{e:#}")),
                );
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

/// Poll two-phase extraction progress for `token`.
///
/// Returns `-1` when there is no active extraction (not started / finished).
/// Otherwise returns `(phase << 16) | permille`, where `phase` is 0 (downloading)
/// or 1 (extracting) and `permille` is 0..=1000. Does no JNI calls, so it takes
/// the env/class directly without `with_env`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_getExtractProgress(
    _env: EnvUnowned,
    _class: JClass,
    token: jlong,
) -> jlong {
    let cell = {
        let map = match EXTRACT_PROGRESS.lock() {
            Ok(m) => m,
            Err(_) => return -1,
        };
        match map.get(&token) {
            Some(c) => c.clone(),
            None => return -1,
        }
    };
    let phase = cell.phase.load(Ordering::Relaxed) as jlong;
    let total = cell.total.load(Ordering::Relaxed);
    let current = cell.current.load(Ordering::Relaxed);
    let permille = if total > 0 {
        ((current.min(total) * 1000) / total) as jlong
    } else {
        0
    };
    (phase << 16) | permille
}

/// Retain the same parsed session under an independent opaque handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_retain(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jlong {
    unowned_env
        .with_env(|env| -> Result<jlong, jni::errors::Error> {
            match session::retain(handle) {
                Ok(handle) => Ok(handle),
                Err(e) => {
                    let _ = env.throw_new(
                        jni_str!("java/lang/RuntimeException"),
                        JNIString::from(e.to_string()),
                    );
                    Ok(0)
                }
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Release only this handle; retained handles and in-flight work stay valid.
#[unsafe(no_mangle)]
pub extern "system" fn Java_native_PayloadExtractNative_close(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    unowned_env
        .with_env(|env| -> Result<(), jni::errors::Error> {
            if let Err(e) = session::close(handle) {
                let _ = env.throw_new(
                    jni_str!("java/lang/RuntimeException"),
                    JNIString::from(e.to_string()),
                );
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}
