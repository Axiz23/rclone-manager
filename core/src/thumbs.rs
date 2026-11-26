//! Server thumbnails: fetch, disk-cache policy and failure back-off. Decoding to bitmaps and the in-memory cache
//! stay in Kotlin (they depend on Android graphics).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;

use crate::error::Result;
use crate::util::{clear_dir, dir_size, now_ms, trim_dir};
use crate::{api, cache};

const DISK_MAX: i64 = 100 * 1024 * 1024;
/// A thumbnail that failed is not requested again for this long.
const RETRY_AFTER_MS: i64 = 5 * 60_000;

static WRITES: AtomicU32 = AtomicU32::new(0);
static FAILED: Mutex<Option<HashMap<String, i64>>> = Mutex::new(None);

fn with_failed<R>(f: impl FnOnce(&mut HashMap<String, i64>) -> R) -> R {
    let mut g = FAILED.lock().unwrap_or_else(|p| p.into_inner());
    f(g.get_or_insert_with(HashMap::new))
}

fn recently_failed(key: &str) -> bool {
    with_failed(|m| match m.get(key) {
        Some(&t) if now_ms() - t <= RETRY_AFTER_MS => true,
        Some(_) => {
            m.remove(key);
            false
        }
        None => false,
    })
}

fn mark_failed(key: &str) {
    with_failed(|m| {
        m.insert(key.to_string(), now_ms());
    });
}

pub fn path(key: &str) -> Result<PathBuf> {
    Ok(cache::thumbs_dir()?.join(format!("{key}.webp")))
}

/// Call after every thumbnail file written; trims the folder every 40 writes.
pub fn wrote() {
    if (WRITES.fetch_add(1, Ordering::Relaxed) + 1) % 40 == 0 {
        if let Ok(d) = cache::thumbs_dir() {
            trim_dir(&d, DISK_MAX);
        }
    }
}

/// Downloads the server thumbnail of a remote file into the cache. Returns its path, or None when the server
/// has none (or a recent attempt failed). The raw WebP bytes are stored as they are, without re-encoding.
pub async fn fetch(remote: &str, file_path: &str, key: &str, tag: &str) -> Result<Option<PathBuf>> {
    if recently_failed(key) {
        return Ok(None);
    }
    match api::thumbnail(remote, file_path, tag).await {
        Ok(Some(bytes)) => {
            let target = path(key)?;
            match tokio::fs::write(&target, &bytes).await {
                Ok(()) => {
                    wrote();
                    Ok(Some(target))
                }
                Err(_) => {
                    mark_failed(key);
                    Ok(None)
                }
            }
        }
        Ok(None) => {
            mark_failed(key);
            Ok(None)
        }
        Err(e) => {
            if !e.is_canceled() {
                mark_failed(key);
            }
            Err(e)
        }
    }
}

/// Forgets failed thumbnails so they are requested again (called after a remote is unlocked).
pub fn forget_failures() {
    with_failed(|m| m.clear());
}

pub fn size() -> Result<i64> {
    Ok(dir_size(&cache::thumbs_dir()?))
}

pub fn clear() -> Result<()> {
    with_failed(|m| m.clear());
    clear_dir(&cache::thumbs_dir()?);
    Ok(())
}
