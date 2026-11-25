//! Data shared between modules.

use serde::{Deserialize, Serialize};
use std::sync::Arc;

/// One directory entry. The short serde names are the on-disk format of the folder-listing cache and of the
/// JSON handed to Kotlin.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Entry {
    #[serde(rename = "n")]
    pub name: String,
    #[serde(rename = "s")]
    pub size: i64,
    #[serde(rename = "d")]
    pub is_dir: bool,
    #[serde(rename = "m")]
    pub modified: i64,
}

/// Progress callbacks. The JNI bridge implements this on top of the Kotlin `CoreListener`; every method has a
/// no-op default so callers only implement what they need.
///
/// Callbacks run on runtime threads: they must not call back into the core synchronously.
pub trait Events: Send + Sync {
    /// `n` more bytes were transferred.
    fn on_bytes(&self, _n: i64) {}
    /// Aggregated file-operation progress (total is 0 when unknown).
    fn on_progress(&self, _done: i64, _total: i64) {}
    /// Backup progress counters.
    fn on_backup(&self, _uploaded: i64, _failed: i64, _bytes: i64) {}
}

pub type Sink = Option<Arc<dyn Events>>;

/// Byte-count callback used by the transfer functions.
pub type ByteCb = Arc<dyn Fn(i64) + Send + Sync>;
