//! File operations. Device-only work uses `std::fs`; anything touching a remote goes through the server API:
//!  - device -> device: plain file copy / rename
//!  - server -> server: `copy` / `move` endpoints (done on the server, no data passes through the phone)
//!  - device -> server: streaming uploads
//!  - server -> device: downloads into `*.part` files that are renamed when complete

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use tokio_util::sync::CancellationToken;

use crate::error::{Error, Result};
use crate::model::{ByteCb, Sink};
use crate::util::now_ms;
use crate::{api, cancel};

/// Cancel tag of file operations (the Kotlin side calls `cancel("op")`).
pub const TAG: &str = "op";

/// A location: on the device (`remote == None`, absolute path) or on a server remote (path relative to its root).
#[derive(Clone, Debug, PartialEq)]
pub struct Loc {
    pub remote: Option<String>,
    pub path: String,
}

impl Loc {
    pub fn child(&self, name: &str) -> String {
        child_path(self.remote.is_some(), &self.path, name)
    }
}

fn child_path(is_remote: bool, dir: &str, name: &str) -> String {
    if !is_remote {
        if dir == "/" || dir.is_empty() {
            format!("/{name}")
        } else {
            format!("{dir}/{name}")
        }
    } else if dir.is_empty() {
        name.to_string()
    } else {
        format!("{dir}/{name}")
    }
}

fn check(tok: &CancellationToken) -> Result<()> {
    if tok.is_cancelled() {
        Err(Error::Canceled)
    } else {
        Ok(())
    }
}

/// Aggregates byte counts and reports at most ~5 times per second.
struct Prog {
    total: i64,
    done: AtomicI64,
    last: AtomicI64,
    events: Sink,
}

impl Prog {
    fn add(&self, n: i64) {
        let done = self.done.fetch_add(n, Ordering::Relaxed) + n;
        let now = now_ms();
        if now - self.last.load(Ordering::Relaxed) > 200 {
            self.last.store(now, Ordering::Relaxed);
            if let Some(e) = &self.events {
                e.on_progress(done, self.total);
            }
        }
    }
}

fn prog_cb(p: &Arc<Prog>) -> ByteCb {
    let p = p.clone();
    Arc::new(move |n| p.add(n))
}

pub async fn mkdir(dir: &Loc, name: &str) -> Result<()> {
    match &dir.remote {
        None => {
            let f = Path::new(&dir.path).join(name);
            if f.exists() {
                return Err(Error::io(format!("\"{name}\" already exists")));
            }
            fs::create_dir_all(&f).map_err(|_| Error::io("Cannot create folder"))
        }
        Some(r) => api::mkdir(r, &dir.child(name), None).await,
    }
}

pub async fn delete(dir: &Loc, name: &str, is_dir: bool) -> Result<()> {
    let guard = cancel::register(TAG);
    check(&guard.token)?;
    match &dir.remote {
        None => {
            let f = Path::new(&dir.path).join(name);
            let r = if is_dir { fs::remove_dir_all(&f) } else { fs::remove_file(&f) };
            r.map_err(|_| Error::io(format!("Cannot delete {name}")))
        }
        Some(r) => api::delete(r, &dir.child(name), is_dir, Some(TAG)).await,
    }
}

pub async fn rename(dir: &Loc, name: &str, new_name: &str) -> Result<()> {
    let guard = cancel::register(TAG);
    check(&guard.token)?;
    match &dir.remote {
        None => {
            let target = Path::new(&dir.path).join(new_name);
            if target.exists() {
                return Err(Error::io(format!("\"{new_name}\" already exists")));
            }
            fs::rename(Path::new(&dir.path).join(name), target).map_err(|_| Error::io("Cannot rename"))
        }
        Some(r) => api::rename(r, &dir.child(name), new_name, Some(TAG)).await,
    }
}

/// "name.ext" -> "name (copy).ext"
fn copy_name(n: &str) -> String {
    match n.rfind('.') {
        Some(dot) if dot > 0 => format!("{} (copy){}", &n[..dot], &n[dot..]),
        _ => format!("{n} (copy)"),
    }
}

fn local_size(p: &Path) -> i64 {
    match fs::metadata(p) {
        Ok(md) if md.is_file() => md.len() as i64,
        Ok(md) if md.is_dir() => fs::read_dir(p)
            .map(|rd| rd.flatten().map(|e| local_size(&e.path())).sum())
            .unwrap_or(0),
        _ => 0,
    }
}

/// Recursive copy that overwrites existing files, like `File.copyRecursively(overwrite = true)`.
fn copy_recursive(src: &Path, dst: &Path, tok: &CancellationToken) -> Result<()> {
    check(tok)?;
    let md = fs::metadata(src)?;
    if md.is_dir() {
        if dst.exists() && !dst.is_dir() {
            fs::remove_file(dst)?;
        }
        fs::create_dir_all(dst)?;
        for ent in fs::read_dir(src)? {
            let ent = ent?;
            copy_recursive(&ent.path(), &dst.join(ent.file_name()), tok)?;
        }
    } else {
        if dst.is_dir() {
            fs::remove_dir_all(dst)?;
        }
        fs::copy(src, dst)?;
    }
    Ok(())
}

fn remove_local(p: &Path, is_dir: bool) -> std::io::Result<()> {
    if is_dir {
        fs::remove_dir_all(p)
    } else {
        fs::remove_file(p)
    }
}

/// Total size of a remote folder (for the progress bar).
async fn remote_size(remote: &str, path: &str, tok: &CancellationToken) -> Result<i64> {
    let mut total = 0i64;
    let mut stack = vec![path.to_string()];
    while let Some(p) = stack.pop() {
        for c in api::list(remote, &p).await? {
            check(tok)?;
            if c.is_dir {
                stack.push(format!("{p}/{}", c.name));
            } else {
                total += c.size;
            }
        }
    }
    Ok(total)
}

async fn upload_tree(root: &Path, remote: &str, dest: &str, tok: &CancellationToken, prog: &Arc<Prog>) -> Result<()> {
    let mut stack: Vec<(PathBuf, String)> = vec![(root.to_path_buf(), dest.to_string())];
    while let Some((local, rpath)) = stack.pop() {
        check(tok)?;
        let md = tokio::fs::metadata(&local).await?;
        if md.is_dir() {
            api::mkdir(remote, &rpath, Some(TAG)).await?;
            let name = local.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            let mut rd = tokio::fs::read_dir(&local)
                .await
                .map_err(|_| Error::io(format!("Cannot read {name}")))?;
            while let Some(ent) = rd.next_entry().await? {
                let n = ent.file_name().to_string_lossy().into_owned();
                stack.push((ent.path(), format!("{rpath}/{n}")));
            }
        } else {
            api::upload(remote, &rpath, &local, TAG, Some(prog_cb(prog))).await?;
        }
    }
    Ok(())
}

async fn download_tree(
    remote: &str,
    path: &str,
    is_dir: bool,
    dest: &Path,
    tok: &CancellationToken,
    prog: &Arc<Prog>,
) -> Result<()> {
    let mut stack: Vec<(String, bool, PathBuf)> = vec![(path.to_string(), is_dir, dest.to_path_buf())];
    while let Some((rpath, dir, local)) = stack.pop() {
        check(tok)?;
        let name = local.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        if dir {
            if !local.is_dir() && fs::create_dir_all(&local).is_err() {
                return Err(Error::io(format!("Cannot create {name}")));
            }
            for c in api::list(remote, &rpath).await? {
                stack.push((format!("{rpath}/{}", c.name), c.is_dir, local.join(&c.name)));
            }
        } else {
            let tmp = local.with_file_name(format!("{name}.part"));
            let r = api::download(remote, &rpath, &tmp, TAG, Some(prog_cb(prog))).await;
            let finished = match r {
                Ok(()) => {
                    let _ = fs::remove_file(&local);
                    fs::rename(&tmp, &local).map_err(|_| Error::io(format!("Cannot write {name}")))
                }
                Err(e) => Err(e),
            };
            let _ = fs::remove_file(&tmp); // no-op after a successful rename
            finished?;
        }
    }
    Ok(())
}

/// Copies or moves one entry from directory `src` to directory `dst` (existing files are overwritten).
pub async fn paste(
    src: &Loc,
    name: &str,
    is_dir: bool,
    size: i64,
    dst: &Loc,
    cut: bool,
    events: Sink,
) -> Result<()> {
    if src == dst && cut {
        return Ok(());
    }
    let dst_name = if src == dst { copy_name(name) } else { name.to_string() };
    let s_path = src.child(name);
    let d_path = dst.child(&dst_name);
    if is_dir && src.remote == dst.remote && (dst.path == s_path || dst.path.starts_with(&format!("{s_path}/"))) {
        return Err(Error::io("Cannot copy a folder into itself"));
    }
    let guard = cancel::register(TAG);
    let tok = guard.token.clone();
    check(&tok)?;

    match (&src.remote, &dst.remote) {
        // device -> device
        (None, None) => {
            let (s, d, t) = (PathBuf::from(&s_path), PathBuf::from(&d_path), tok.clone());
            tokio::task::spawn_blocking(move || -> Result<()> {
                if cut {
                    if fs::rename(&s, &d).is_err() {
                        copy_recursive(&s, &d, &t)?;
                        let _ = remove_local(&s, s.is_dir());
                    }
                    Ok(())
                } else {
                    copy_recursive(&s, &d, &t)
                }
            })
            .await
            .map_err(|e| Error::io(e.to_string()))?
        }
        // server -> server
        (Some(sr), Some(dr)) => {
            if cut {
                api::move_(sr, &s_path, dr, &d_path, Some(TAG)).await
            } else {
                api::copy(sr, &s_path, dr, &d_path, Some(TAG)).await
            }
        }
        // device -> server
        (None, Some(dr)) => {
            let root = PathBuf::from(&s_path);
            let prog = Arc::new(Prog { total: local_size(&root), done: AtomicI64::new(0), last: AtomicI64::new(0), events });
            upload_tree(&root, dr, &d_path, &tok, &prog).await?;
            if cut && remove_local(&root, is_dir).is_err() {
                return Err(Error::io("Copied, but cannot delete the original"));
            }
            Ok(())
        }
        // server -> device
        (Some(sr), None) => {
            let total = if is_dir { remote_size(sr, &s_path, &tok).await? } else { size };
            let prog = Arc::new(Prog { total, done: AtomicI64::new(0), last: AtomicI64::new(0), events });
            download_tree(sr, &s_path, is_dir, Path::new(&d_path), &tok, &prog).await?;
            if cut {
                api::delete(sr, &s_path, is_dir, Some(TAG)).await?;
            }
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn copy_names() {
        assert_eq!(copy_name("a.txt"), "a (copy).txt");
        assert_eq!(copy_name("noext"), "noext (copy)");
        assert_eq!(copy_name(".hidden"), ".hidden (copy)");
    }

    #[test]
    fn child_paths() {
        assert_eq!(child_path(false, "/", "a"), "/a");
        assert_eq!(child_path(false, "/x", "a"), "/x/a");
        assert_eq!(child_path(true, "", "a"), "a");
        assert_eq!(child_path(true, "d", "a"), "d/a");
    }

    #[test]
    fn folder_into_itself_is_refused() {
        let l = Loc { remote: None, path: "/tmp/rcm-none".into() };
        let sub = Loc { remote: None, path: "/tmp/rcm-none/dir/sub".into() };
        let r = crate::state::block_on(paste(&l, "dir", true, 0, &sub, false, None));
        assert!(r.is_err());
    }
}
