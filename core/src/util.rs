//! Small helpers: hashing, time, size formatting, URL encoding, cache-directory trimming.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub fn md5_hex(s: &str) -> String {
    format!("{:x}", md5::compute(s.as_bytes()))
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Last-modified time in milliseconds since the epoch (0 when unknown), like `File.lastModified()`.
pub fn mtime_ms(md: &fs::Metadata) -> i64 {
    md.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// First `n` characters of `s`.
pub fn take_chars(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

/// `a/b`, or just `b` when `a` is empty.
pub fn join(a: &str, b: &str) -> String {
    if a.is_empty() {
        b.to_string()
    } else {
        format!("{a}/{b}")
    }
}

/// Human-readable size with SI units ("1.5 MB"), close to Android's `Formatter.formatShortFileSize`.
pub fn fmt_size(bytes: i64) -> String {
    let b = bytes.max(0);
    if b < 1000 {
        return format!("{b} B");
    }
    let units = ["kB", "MB", "GB", "TB", "PB"];
    let mut v = b as f64 / 1000.0;
    let mut i = 0;
    while v >= 1000.0 && i < units.len() - 1 {
        v /= 1000.0;
        i += 1;
    }
    if v < 10.0 {
        format!("{:.2} {}", v, units[i])
    } else if v < 100.0 {
        format!("{:.1} {}", v, units[i])
    } else {
        format!("{:.0} {}", v, units[i])
    }
}

// ---------------------------------------------------------------------------------------------------------------
// URL encoding. These reproduce, byte for byte, what the previous OkHttp / android.net.Uri code put on the wire,
// so the server sees exactly the same requests.

const HEX: &[u8; 16] = b"0123456789ABCDEF";

fn percent_encode(s: &str, extra: &[u8]) -> String {
    let mut out = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        if b < 0x20 || b == 0x7f || b >= 0x80 || b == b'%' || extra.contains(&b) {
            out.push('%');
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 15) as usize] as char);
        } else {
            out.push(b as char);
        }
    }
    out
}

/// OkHttp `addQueryParameter` encoding: space becomes `%20`, `+` is left as is.
pub fn enc_query(s: &str) -> String {
    percent_encode(s, b" !\"#$&'(),/:;<=>?@[]\\^`{|}~")
}

/// OkHttp path-segment encoding (`addPathSegments`, not "already encoded").
pub fn enc_path_segment(s: &str) -> String {
    percent_encode(s, b" \"<>^`{}|/\\?#")
}

/// `android.net.Uri.encode`: keeps letters, digits and `_-!.~'()*`.
pub fn android_uri_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        if b.is_ascii_alphanumeric() || b"_-!.~'()*".contains(&b) {
            out.push(b as char);
        } else {
            out.push('%');
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 15) as usize] as char);
        }
    }
    out
}

/// Base64url without padding (the id format used by the thumbnail endpoint).
pub fn b64url(data: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity((data.len() * 4 + 2) / 3);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(T[((n >> 18) & 63) as usize] as char);
        out.push(T[((n >> 12) & 63) as usize] as char);
        if chunk.len() > 1 {
            out.push(T[((n >> 6) & 63) as usize] as char);
        }
        if chunk.len() > 2 {
            out.push(T[(n & 63) as usize] as char);
        }
    }
    out
}

// ---------------------------------------------------------------------------------------------------------------
// Cache directories

/// Total size of the files directly inside `dir`.
pub fn dir_size(dir: &Path) -> i64 {
    let mut total = 0i64;
    if let Ok(rd) = fs::read_dir(dir) {
        for ent in rd.flatten() {
            if let Ok(md) = ent.metadata() {
                if md.is_file() {
                    total += md.len() as i64;
                }
            }
        }
    }
    total
}

/// When `dir` is larger than `max` bytes, deletes the oldest files until it is down to 80 % of `max`.
pub fn trim_dir(dir: &Path, max: i64) {
    let rd = match fs::read_dir(dir) {
        Ok(r) => r,
        Err(_) => return,
    };
    let mut files: Vec<(i64, i64, PathBuf)> = Vec::new(); // (modified, length, path)
    let mut total = 0i64;
    for ent in rd.flatten() {
        if let Ok(md) = ent.metadata() {
            if md.is_file() {
                total += md.len() as i64;
                files.push((mtime_ms(&md), md.len() as i64, ent.path()));
            }
        }
    }
    if total <= max {
        return;
    }
    files.sort_by_key(|f| f.0);
    for (_, len, path) in files {
        if total <= max * 8 / 10 {
            break;
        }
        total -= len;
        let _ = fs::remove_file(path);
    }
}

/// Deletes every file directly inside `dir`.
pub fn clear_dir(dir: &Path) {
    if let Ok(rd) = fs::read_dir(dir) {
        for ent in rd.flatten() {
            let _ = fs::remove_file(ent.path());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_encoding_matches_okhttp() {
        assert_eq!(enc_query("a b"), "a%20b");
        assert_eq!(enc_query("a+b"), "a+b");
        assert_eq!(enc_query("100%"), "100%25");
        assert_eq!(enc_query("é"), "%C3%A9");
        assert_eq!(enc_query("a/b&c"), "a%2Fb%26c");
    }

    #[test]
    fn path_segment_encoding() {
        assert_eq!(enc_path_segment("a b"), "a%20b");
        assert_eq!(enc_path_segment("x%y"), "x%25y");
        assert_eq!(enc_path_segment("files"), "files");
    }

    #[test]
    fn android_uri_encode_keeps_unreserved() {
        assert_eq!(android_uri_encode("a-b_c.d~e!f'g(h)i*j"), "a-b_c.d~e!f'g(h)i*j");
        assert_eq!(android_uri_encode("a b/c"), "a%20b%2Fc");
    }

    #[test]
    fn base64url_without_padding() {
        assert_eq!(b64url(b"a:b"), "YTpi");
        assert_eq!(b64url(b"a"), "YQ");
        assert_eq!(b64url(b"ab"), "YWI");
        assert_eq!(b64url(b"\xfb\xff"), "-_8");
    }

    #[test]
    fn sizes() {
        assert_eq!(fmt_size(0), "0 B");
        assert_eq!(fmt_size(999), "999 B");
        assert_eq!(fmt_size(1500), "1.50 kB");
        assert_eq!(fmt_size(12_300_000), "12.3 MB");
    }

    #[test]
    fn join_paths() {
        assert_eq!(join("", "a"), "a");
        assert_eq!(join("a", "b"), "a/b");
    }
}
