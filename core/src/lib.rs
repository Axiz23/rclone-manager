//! Rclone Manager core.
//!
//! Everything that is not Android UI or platform glue lives here: the API client driven by the adapter profile,
//! the backup engine, file operations, disk caches, thumbnail fetching, job storage and logging. Kotlin keeps the
//! views, the player, notifications, alarms and bitmap decoding, and talks to this crate through one JNI function
//! (see `bridge.rs`) that takes a method name and JSON arguments (see `dispatch.rs`).

mod adapter;
mod api;
mod backup;
mod bridge;
mod cache;
mod cancel;
mod dispatch;
mod error;
mod glob;
mod jobs;
mod logs;
mod model;
mod ops;
mod state;
mod thumbs;
mod util;

use std::sync::Once;

static INSTALL: Once = Once::new();

/// One-time process setup: TLS provider and a panic hook that writes to the client log.
pub(crate) fn install_once() {
    INSTALL.call_once(|| {
        // Ignore the error: another provider may already be installed.
        let _ = rustls::crypto::ring::default_provider().install_default();
        std::panic::set_hook(Box::new(|info| {
            logs::error("Core", &format!("panic: {info}"));
        }));
    });
}
