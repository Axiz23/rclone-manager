//! Backup through the API: walks the source folder, compares every directory with the server listing, uploads
//! what is missing or changed, and in sync mode deletes what no longer exists locally.
//!
//! Exit codes: 0 ok, 1 some files failed, 130 cancelled, -1 failed with an exception, -3 server not configured,
//! -4 source folder not readable.

use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;

use crate::error::{Error, Result};
use crate::glob::Glob;
use crate::jobs::Job;
use crate::model::{ByteCb, Entry, Sink};
use crate::util::{fmt_size, join, mtime_ms, now_ms, take_chars};
use crate::{api, cancel, logs};

/// Cancel tag of backups (the Kotlin side calls `cancel("backup")`).
pub const TAG: &str = "backup";

/// Counters and the per-run log lines, shared by all upload tasks.
struct Stats {
    uploaded: AtomicI64,
    failed: AtomicI64,
    deleted: AtomicI64,
    bytes: AtomicI64,
    last_emit: AtomicI64,
    ring: Mutex<VecDeque<String>>,
    events: Sink,
}

impl Stats {
    fn log(&self, line: impl AsRef<str>) {
        let mut ring = self.ring.lock().unwrap_or_else(|p| p.into_inner());
        if ring.len() >= logs::RUN_LINES {
            ring.pop_front();
        }
        ring.push_back(take_chars(line.as_ref(), logs::MAX_LINE));
    }

    /// Reports the counters (at most about 5 times per second unless `force`).
    fn progress(&self, force: bool) {
        let now = now_ms();
        if !force && now - self.last_emit.load(Ordering::Relaxed) < 200 {
            return;
        }
        self.last_emit.store(now, Ordering::Relaxed);
        if let Some(e) = &self.events {
            e.on_backup(
                self.uploaded.load(Ordering::Relaxed),
                self.failed.load(Ordering::Relaxed),
                self.bytes.load(Ordering::Relaxed),
            );
        }
    }
}

/// One entry of a local directory.
struct Child {
    name: String,
    path: PathBuf,
    is_file: bool,
    is_dir: bool,
    size: i64,
    modified: i64,
}

fn read_children(dir: &std::path::Path) -> Option<Vec<Child>> {
    let rd = std::fs::read_dir(dir).ok()?;
    let mut out = Vec::new();
    for ent in rd.flatten() {
        let path = ent.path();
        // metadata() follows symlinks, like File.isFile / isDirectory.
        if let Ok(md) = std::fs::metadata(&path) {
            out.push(Child {
                name: ent.file_name().to_string_lossy().into_owned(),
                is_file: md.is_file(),
                is_dir: md.is_dir(),
                size: md.len() as i64,
                modified: mtime_ms(&md),
                path,
            });
        }
    }
    Some(out)
}

fn patterns(s: &str) -> Vec<Glob> {
    s.lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .map(Glob::new)
        .collect()
}

fn allowed(rel: &str, exclude: &[Glob], include: &[Glob]) -> bool {
    !exclude.iter().any(|g| g.matches(rel)) && (include.is_empty() || include.iter().any(|g| g.matches(rel)))
}

/// Uploads one file with up to 3 attempts; client errors (4xx) are not retried.
async fn upload_one(
    _permit: OwnedSemaphorePermit,
    st: Arc<Stats>,
    tok: CancellationToken,
    remote: String,
    remote_path: String,
    file: PathBuf,
    rel: String,
) {
    let mut last_error = String::new();
    for attempt in 0..3u64 {
        if tok.is_cancelled() {
            return;
        }
        let counter = st.clone();
        let cb: ByteCb = Arc::new(move |n| {
            counter.bytes.fetch_add(n, Ordering::Relaxed);
        });
        match api::upload(&remote, &remote_path, &file, TAG, Some(cb)).await {
            Ok(()) => {
                st.uploaded.fetch_add(1, Ordering::Relaxed);
                st.progress(false);
                return;
            }
            Err(e) => {
                if tok.is_cancelled() {
                    return;
                }
                last_error = e.message();
                if let Error::Api { status, .. } = &e {
                    if (400..=499).contains(status) {
                        break;
                    }
                }
                if attempt < 2 {
                    tokio::select! {
                        _ = tokio::time::sleep(Duration::from_millis(1500 * (attempt + 1))) => {}
                        _ = tok.cancelled() => return,
                    }
                }
            }
        }
    }
    st.failed.fetch_add(1, Ordering::Relaxed);
    st.log(format!("FAILED {rel}: {last_error}"));
    st.progress(true);
}

async fn execute(job: &Job, tok: &CancellationToken, st: &Arc<Stats>) -> Result<i32> {
    if !api::configured() {
        st.log("Server is not configured");
        return Ok(-3);
    }
    let root = PathBuf::from(&job.source);
    if !root.is_dir() || read_children(&root).is_none() {
        st.log(format!("Source folder is not readable: {}", job.source));
        return Ok(-4);
    }
    let exclude = patterns(&job.exclude);
    let include = patterns(&job.include);
    let sem = Arc::new(Semaphore::new(job.transfers.clamp(1, 8) as usize));
    // Dropping the set (on an error) aborts the running uploads.
    let mut tasks: JoinSet<()> = JoinSet::new();

    // Depth-first walk of the source tree.
    let mut stack: Vec<(PathBuf, String)> = vec![(root, String::new())];
    'walk: while let Some((dir, rel)) = stack.pop() {
        if tok.is_cancelled() {
            break;
        }
        let children = match read_children(&dir) {
            Some(c) => c,
            None => {
                st.failed.fetch_add(1, Ordering::Relaxed);
                st.log(format!("Cannot read {}", if rel.is_empty() { "/" } else { rel.as_str() }));
                continue;
            }
        };
        let remote_dir = join(&job.remote_path, &rel);
        let files: Vec<&Child> =
            children.iter().filter(|c| c.is_file && allowed(&join(&rel, &c.name), &exclude, &include)).collect();

        // The remote listing is only needed when there is something to compare or to delete.
        let remote_list: Option<Vec<Entry>> = if !files.is_empty() || job.sync {
            match api::list(&job.remote, &remote_dir).await {
                Ok(l) => Some(l),
                Err(Error::Api { status: 404, .. }) => Some(Vec::new()),
                Err(e) if e.is_canceled() => break,
                Err(e) => return Err(e),
            }
        } else {
            None
        };

        if let Some(list) = &remote_list {
            let by_name: HashMap<&str, &Entry> = list.iter().map(|e| (e.name.as_str(), e)).collect();
            for f in &files {
                let needs_upload = match by_name.get(f.name.as_str()) {
                    None => true,
                    Some(r) => r.is_dir || r.size != f.size || f.modified > r.modified + 2000,
                };
                if !needs_upload {
                    continue;
                }
                // Waiting for a free slot keeps memory bounded on huge trees.
                let permit = tokio::select! {
                    p = sem.clone().acquire_owned() => p.ok(),
                    _ = tok.cancelled() => None,
                };
                let Some(permit) = permit else { break 'walk };
                tasks.spawn(upload_one(
                    permit,
                    st.clone(),
                    tok.clone(),
                    job.remote.clone(),
                    join(&remote_dir, &f.name),
                    f.path.clone(),
                    join(&rel, &f.name),
                ));
            }

            if job.sync {
                let local: HashSet<&str> = children.iter().map(|c| c.name.as_str()).collect();
                for e in list {
                    if tok.is_cancelled() {
                        break;
                    }
                    if local.contains(e.name.as_str()) {
                        continue;
                    }
                    let rel_name = join(&rel, &e.name);
                    // Files excluded by the filters are left alone on the server.
                    if !e.is_dir && !allowed(&rel_name, &exclude, &include) {
                        continue;
                    }
                    match api::delete(&job.remote, &join(&remote_dir, &e.name), e.is_dir, Some(TAG)).await {
                        Ok(()) => {
                            st.deleted.fetch_add(1, Ordering::Relaxed);
                            st.log(format!("Deleted {rel_name}"));
                        }
                        Err(err) => {
                            if !tok.is_cancelled() {
                                st.failed.fetch_add(1, Ordering::Relaxed);
                                st.log(format!("FAILED delete {rel_name}: {}", err.message()));
                            }
                        }
                    }
                }
            }
        }

        // Every sub-folder is visited; the filters only apply to files. Pushed in reverse to keep the order.
        for d in children.iter().filter(|c| c.is_dir).rev() {
            stack.push((d.path.clone(), join(&rel, &d.name)));
        }
    }

    while tasks.join_next().await.is_some() {}
    st.progress(true);
    let code = if tok.is_cancelled() {
        130
    } else if st.failed.load(Ordering::Relaxed) > 0 {
        1
    } else {
        0
    };
    st.log(format!(
        "Done: {} uploaded ({}), {} failed, {} deleted",
        st.uploaded.load(Ordering::Relaxed),
        fmt_size(st.bytes.load(Ordering::Relaxed)),
        st.failed.load(Ordering::Relaxed),
        st.deleted.load(Ordering::Relaxed)
    ));
    Ok(code)
}

/// Runs one job to completion, appends the run to the job log and returns the exit code.
pub async fn run(job: Job, events: Sink) -> i32 {
    let guard = cancel::register(TAG);
    let tok = guard.token.clone();
    let st = Arc::new(Stats {
        uploaded: AtomicI64::new(0),
        failed: AtomicI64::new(0),
        deleted: AtomicI64::new(0),
        bytes: AtomicI64::new(0),
        last_emit: AtomicI64::new(0),
        ring: Mutex::new(VecDeque::new()),
        events,
    });

    let code = match execute(&job, &tok, &st).await {
        Ok(c) => c,
        Err(e) => {
            if !tok.is_cancelled() {
                st.log(format!("Exception: {}", e.message()));
            }
            -1
        }
    };

    let mode = if job.sync { "sync" } else { "copy" };
    let mut block = vec![
        format!("=== {} | exit={} ===", logs::format_local(now_ms(), "%Y-%m-%d %H:%M:%S"), code),
        format!("{} -> {} ({})", job.source, job.dest(), mode),
    ];
    block.extend(st.ring.lock().unwrap_or_else(|p| p.into_inner()).iter().cloned());
    let _ = logs::job_append(&job.id, &block);
    logs::info(
        "Backup",
        &format!(
            "{}: exit={}, {} uploaded, {} failed, {} deleted",
            job.name,
            code,
            st.uploaded.load(Ordering::Relaxed),
            st.failed.load(Ordering::Relaxed),
            st.deleted.load(Ordering::Relaxed)
        ),
    );
    code
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filters() {
        let ex = patterns("# comment\n*.tmp\n\n");
        let inc = patterns("*.jpg\n*.png");
        assert_eq!(ex.len(), 1);
        assert!(!allowed("a.tmp", &ex, &[]));
        assert!(allowed("a.jpg", &ex, &inc));
        assert!(!allowed("a.txt", &ex, &inc));
        assert!(allowed("anything", &[], &[]));
    }
}
