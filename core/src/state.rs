//! Process-wide state: directories, time-zone offset and the async runtime.

use std::future::Future;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{OnceLock, RwLock};

use tokio::runtime::Runtime;

use crate::error::{Error, Result};

#[derive(Clone)]
pub struct Paths {
    /// `Context.filesDir` (jobs, job logs, client log).
    pub files: PathBuf,
    /// `Context.cacheDir` (folder listings, photos, thumbnails).
    pub cache: PathBuf,
}

static PATHS: RwLock<Option<Paths>> = RwLock::new(None);
static TZ_OFFSET: AtomicI32 = AtomicI32::new(0);
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

pub fn set_paths(files: &str, cache: &str) {
    let mut g = PATHS.write().unwrap_or_else(|p| p.into_inner());
    *g = Some(Paths { files: PathBuf::from(files), cache: PathBuf::from(cache) });
}

pub fn paths() -> Result<Paths> {
    PATHS
        .read()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
        .ok_or_else(|| Error::io("Core is not initialised"))
}

/// Local UTC offset in seconds. Android's libc is not reliable for this from native code, so Kotlin supplies it.
pub fn set_tz_offset(seconds: i32) {
    TZ_OFFSET.store(seconds, Ordering::Relaxed);
}

pub fn tz_offset() -> i32 {
    TZ_OFFSET.load(Ordering::Relaxed)
}

/// Small shared runtime: two workers are enough because all the work is I/O bound.
pub fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("rcm-io")
            .enable_all()
            .build()
            .expect("cannot start the async runtime")
    })
}

/// Runs a future to completion on the calling thread (all public entry points are blocking).
pub fn block_on<F: Future>(f: F) -> F::Output {
    runtime().block_on(f)
}
