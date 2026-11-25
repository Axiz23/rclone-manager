//! Cancellation by tag, mirroring the `Api.cancel(tag)` behaviour of the previous OkHttp client: only calls that
//! are in flight when `cancel` runs are aborted; calls started afterwards are unaffected.

use std::future::Future;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use tokio_util::sync::CancellationToken;

use crate::error::{Error, Result};

struct Entry {
    id: u64,
    tag: String,
    token: CancellationToken,
}

static REGISTRY: Mutex<Vec<Entry>> = Mutex::new(Vec::new());
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// Registers a cancellable unit of work; it is unregistered when the guard is dropped.
pub struct Guard {
    id: u64,
    pub token: CancellationToken,
}

impl Drop for Guard {
    fn drop(&mut self) {
        let mut g = REGISTRY.lock().unwrap_or_else(|p| p.into_inner());
        g.retain(|e| e.id != self.id);
    }
}

pub fn register(tag: &str) -> Guard {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let token = CancellationToken::new();
    let mut g = REGISTRY.lock().unwrap_or_else(|p| p.into_inner());
    g.push(Entry { id, tag: tag.to_string(), token: token.clone() });
    Guard { id, token }
}

/// Aborts everything registered under `tag` right now.
pub fn cancel(tag: &str) {
    let mut g = REGISTRY.lock().unwrap_or_else(|p| p.into_inner());
    g.retain(|e| {
        if e.tag == tag {
            e.token.cancel();
            false
        } else {
            true
        }
    });
}

/// Runs `fut`, aborting it with `Error::Canceled` when `cancel(tag)` is called. Without a tag it just runs.
pub async fn with_cancel<T, F>(tag: Option<&str>, fut: F) -> Result<T>
where
    F: Future<Output = Result<T>>,
{
    match tag {
        None => fut.await,
        Some(t) => {
            let guard = register(t);
            tokio::select! {
                biased;
                _ = guard.token.cancelled() => Err(Error::Canceled),
                r = fut => r,
            }
        }
    }
}
