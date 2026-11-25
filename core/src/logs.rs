//! Logging: the persistent client log (`filesDir/client.log`, shown in Settings → Logs), logcat output and the
//! per-job backup logs (`filesDir/logs/<id>.log`). All log text is English and never contains tokens.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use chrono::{DateTime, FixedOffset, Utc};
use regex::Regex;

use crate::error::{Error, Result};
use crate::{state, util};

/// The client log is cut down to its newest 60 % when it grows past this size.
const CLIENT_LOG_MAX: u64 = 400_000;

/// Per-job log limits.
pub const STORE_LINES: usize = 200; // lines kept on disk per job
pub const RUN_LINES: usize = 80; // lines captured per run
pub const MAX_LINE: usize = 300; // characters per line

static FILE_LOCK: Mutex<()> = Mutex::new(());

fn lock() -> std::sync::MutexGuard<'static, ()> {
    FILE_LOCK.lock().unwrap_or_else(|p| p.into_inner())
}

// ---------------------------------------------------------------------------------------------------------------
// Client log

fn scrubbers() -> &'static (Regex, Regex) {
    static R: OnceLock<(Regex, Regex)> = OnceLock::new();
    R.get_or_init(|| {
        (
            Regex::new(r"(?i)(token|key|authorization)=[^&\s]+").expect("valid regex"),
            Regex::new(r"Bearer\s+\S+").expect("valid regex"),
        )
    })
}

/// Removes anything that looks like a credential before it is written anywhere.
pub fn scrub(s: &str) -> String {
    let (query, bearer) = scrubbers();
    let a = query.replace_all(s, "${1}=***");
    bearer.replace_all(&a, "Bearer ***").into_owned()
}

fn offset() -> FixedOffset {
    FixedOffset::east_opt(state::tz_offset()).unwrap_or_else(|| FixedOffset::east_opt(0).expect("zero offset"))
}

/// Formats `ms` in the device's local time with a chrono format string.
pub fn format_local(ms: i64, fmt: &str) -> String {
    match DateTime::<Utc>::from_timestamp_millis(ms) {
        Some(d) => d.with_timezone(&offset()).format(fmt).to_string(),
        None => String::new(),
    }
}

fn client_log_path() -> Result<PathBuf> {
    Ok(state::paths()?.files.join("client.log"))
}

/// Appends one entry (and an optional stack trace) to the client log and to logcat.
pub fn write(level: &str, tag: &str, msg: &str, stack: &str) {
    let msg = scrub(msg);
    logcat(level, tag, &msg);
    let path = match client_log_path() {
        Ok(p) => p,
        Err(_) => return,
    };
    let mut text = format!("{} {} {}: {}\n", format_local(util::now_ms(), "%m-%d %H:%M:%S%.3f"), level, tag, msg);
    for line in stack.lines().filter(|l| !l.trim().is_empty()) {
        text.push_str("    ");
        text.push_str(&scrub(line.trim()));
        text.push('\n');
    }
    let _guard = lock();
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) {
        let _ = f.write_all(text.as_bytes());
    }
    rotate(&path);
}

pub fn info(tag: &str, msg: &str) {
    write("I", tag, msg, "");
}

pub fn warn(tag: &str, msg: &str) {
    write("W", tag, msg, "");
}

pub fn error(tag: &str, msg: &str) {
    write("E", tag, msg, "");
}

fn rotate(path: &Path) {
    let len = match fs::metadata(path) {
        Ok(m) => m.len(),
        Err(_) => return,
    };
    if len <= CLIENT_LOG_MAX {
        return;
    }
    let bytes = match fs::read(path) {
        Ok(b) => b,
        Err(_) => return,
    };
    let text = String::from_utf8_lossy(&bytes).into_owned();
    let mut start = (text.len() as f64 * 0.4) as usize;
    while start < text.len() && !text.is_char_boundary(start) {
        start += 1;
    }
    let tail = &text[start..];
    // Start at a line boundary so the first line is never cut in half.
    let tail = match tail.find('\n') {
        Some(i) => &tail[i + 1..],
        None => tail,
    };
    let _ = fs::write(path, tail);
}

pub fn read() -> String {
    let _guard = lock();
    match client_log_path() {
        Ok(p) => fs::read(p).map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_default(),
        Err(_) => String::new(),
    }
}

pub fn clear() {
    let _guard = lock();
    if let Ok(p) = client_log_path() {
        let _ = fs::remove_file(p);
    }
}

#[cfg(target_os = "android")]
fn logcat(level: &str, tag: &str, msg: &str) {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
    let priority = if level == "I" { 4 } else { 6 }; // ANDROID_LOG_INFO / ANDROID_LOG_ERROR
    if let (Ok(t), Ok(m)) = (CString::new(format!("RcloneMgr/{tag}")), CString::new(msg.replace('\0', " "))) {
        // SAFETY: both pointers come from live, NUL-terminated CStrings.
        unsafe {
            __android_log_write(priority, t.as_ptr(), m.as_ptr());
        }
    }
}

#[cfg(not(target_os = "android"))]
fn logcat(_level: &str, _tag: &str, _msg: &str) {}

// ---------------------------------------------------------------------------------------------------------------
// Per-job logs

fn job_log_path(id: &str) -> Result<PathBuf> {
    if id.is_empty() || id.contains('/') || id.contains('\\') || id.contains("..") {
        return Err(Error::io("Invalid job id"));
    }
    Ok(state::paths()?.files.join("logs").join(format!("{id}.log")))
}

/// Appends `lines` and keeps only the newest `STORE_LINES`.
pub fn job_append(id: &str, lines: &[String]) -> Result<()> {
    let path = job_log_path(id)?;
    let _guard = lock();
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let old = fs::read_to_string(&path).unwrap_or_default();
    let mut all: Vec<&str> = old.lines().collect();
    all.extend(lines.iter().map(|s| s.as_str()));
    let start = all.len().saturating_sub(STORE_LINES);
    fs::write(&path, all[start..].join("\n"))?;
    Ok(())
}

/// The newest `limit` lines of a job log.
pub fn job_read(id: &str, limit: usize) -> Result<Vec<String>> {
    let path = job_log_path(id)?;
    let _guard = lock();
    let text = fs::read_to_string(&path).unwrap_or_default();
    let all: Vec<&str> = text.lines().collect();
    let start = all.len().saturating_sub(limit);
    Ok(all[start..].iter().map(|s| s.to_string()).collect())
}

pub fn job_clear(id: &str) -> Result<()> {
    let path = job_log_path(id)?;
    let _guard = lock();
    let _ = fs::remove_file(path);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scrub_removes_credentials() {
        assert_eq!(scrub("GET /x?token=abc123&a=1"), "GET /x?token=***&a=1");
        assert_eq!(scrub("Authorization: Bearer abc.def"), "Authorization: Bearer ***");
        assert_eq!(scrub("nothing secret"), "nothing secret");
    }

    #[test]
    fn local_time_uses_the_offset() {
        state::set_tz_offset(3600);
        assert_eq!(format_local(0, "%Y-%m-%d %H:%M:%S"), "1970-01-01 01:00:00");
        state::set_tz_offset(0);
    }
}
