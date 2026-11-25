//! Disk caches under `cacheDir` (Android may reclaim them at any time):
//!  - `lists/`  folder listings, shown instantly and then refreshed from the server
//!  - `files/`  original photos opened in the viewer
//!  - `thumbs/` thumbnails (see `thumbs.rs`)
//! The Media3 video cache lives next to them but is managed by Kotlin (`VideoCache`).

use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};

use crate::error::Result;
use crate::model::Entry;
use crate::state;
use crate::util::{clear_dir, dir_size, md5_hex, trim_dir};

const LISTS_MAX: i64 = 20 * 1024 * 1024;

static LIST_WRITES: AtomicU32 = AtomicU32::new(0);

fn dir(name: &str) -> Result<PathBuf> {
    let p = state::paths()?.cache.join(name);
    let _ = fs::create_dir_all(&p);
    Ok(p)
}

pub fn lists_dir() -> Result<PathBuf> {
    dir("lists")
}

pub fn files_dir() -> Result<PathBuf> {
    dir("files")
}

pub fn thumbs_dir() -> Result<PathBuf> {
    dir("thumbs")
}

fn list_file(remote: &str, path: &str) -> Result<PathBuf> {
    Ok(lists_dir()?.join(format!("{}.json", md5_hex(&format!("{remote}:{path}")))))
}

pub fn put_list(remote: &str, path: &str, entries: &[Entry]) -> Result<()> {
    fs::write(list_file(remote, path)?, serde_json::to_vec(entries)?)?;
    // Trim now and then (about every 20th write) instead of on every write.
    if LIST_WRITES.fetch_add(1, Ordering::Relaxed) % 20 == 19 {
        trim_dir(&lists_dir()?, LISTS_MAX);
    }
    Ok(())
}

/// The cached listing, or None when there is none (or it cannot be read).
pub fn get_list(remote: &str, path: &str) -> Option<Vec<Entry>> {
    let bytes = fs::read(list_file(remote, path).ok()?).ok()?;
    serde_json::from_slice(&bytes).ok()
}

/// Where the original of a photo is stored. The key includes size and modification time, so a changed file is
/// fetched again.
pub fn photo_path(remote: &str, path: &str, size: i64, modified: i64) -> Result<PathBuf> {
    Ok(files_dir()?.join(md5_hex(&format!("{remote}:{path}|{size}|{modified}"))))
}

pub fn trim_files(limit: i64) -> Result<()> {
    trim_dir(&files_dir()?, limit);
    Ok(())
}

/// Size of listings + photos (video cache and thumbnails are counted by their owners).
pub fn size() -> Result<i64> {
    Ok(dir_size(&lists_dir()?) + dir_size(&files_dir()?))
}

pub fn clear() -> Result<()> {
    clear_dir(&lists_dir()?);
    clear_dir(&files_dir()?);
    Ok(())
}
