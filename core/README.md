# rcm-core

Rust core of Rclone Manager (everything except Android UI/platform glue). Built as `librcm_core.so` for arm64 and
loaded by `app.rclonemgr.Core`.

| Module | Role |
|---|---|
| `adapter.rs` | JSON connection profile (URL prefix, auth, endpoints, fields, timeouts), placeholder filling |
| `api.rs` | HTTP client (reqwest/rustls, HTTP/2): list, stream URL, thumbnails, download, upload, mkdir/rename/delete/copy/move |
| `backup.rs`, `glob.rs` | Backup engine and rclone-style include/exclude patterns |
| `ops.rs` | Copy / move / delete / rename across device and server |
| `cache.rs`, `thumbs.rs` | Folder-listing and photo caches, server thumbnails with failure back-off |
| `jobs.rs`, `logs.rs` | Job store (`jobs.json`), client log and per-job logs |
| `cancel.rs` | Cancel by tag (`Core.run("cancel", {tag})`) |
| `dispatch.rs`, `bridge.rs` | Method router and the single JNI entry point |

## Adding a core method
1. Add an arm to `dispatch.rs` (JSON args in, JSON value out).
2. Call it from Kotlin: `Core.run("name", Core.args("k" to v))`.

## Notes
- Calls block the calling thread; never call the core from inside a `CoreListener` callback.
- Errors map to Java exceptions in `bridge.rs`; cancellation is `IOException("Canceled")`.
- Local checks: `cargo test` (host). Android build: `cargo ndk -t arm64-v8a -P 30 -o ../app/src/main/jniLibs build --release`.
