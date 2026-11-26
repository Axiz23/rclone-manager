//! Client for the server API.
//!
//! HOW it talks (paths, methods, auth, field names, timeouts) comes from the [`Adapter`] profile. The requests put
//! on the wire (URLs, encoding, headers, bodies) are the same as those of the previous OkHttp client; only the
//! HTTP library changed. All functions are async: the JNI layer blocks on them from Kotlin worker threads.

use std::future::Future;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::Duration;

use futures_util::StreamExt;
use reqwest::header::{
    HeaderMap, HeaderName, HeaderValue, AUTHORIZATION, CONTENT_LENGTH, CONTENT_TYPE, USER_AGENT,
};
use reqwest::{Client, Method, RequestBuilder, Response, Url};
use serde_json::Value;
use tokio::io::AsyncWriteExt;
use tokio::time::timeout;
use tokio_util::io::ReaderStream;

use crate::adapter::{self, Adapter, Endpoint, Var, Vars};
use crate::cancel::with_cancel;
use crate::error::{Error, Result};
use crate::model::{ByteCb, Entry};
use crate::util::{b64url, enc_path_segment, enc_query, mtime_ms, now_ms, take_chars};
use crate::{cache, logs};

/// Sent exactly as the previous client did, so server-side filters and logs see no change.
const USER_AGENT_VALUE: &str = "okhttp/4.12.0";

// ---------------------------------------------------------------------------------------------------------------
// Configuration

struct Cfg {
    base: String,
    token: String,
    adapter: Arc<Adapter>,
    /// Normalised JSON text the current adapter was parsed from (to skip needless rebuilds).
    adapter_src: String,
    max_upload: i64,
    client: Option<Client>,
    /// Remote-lock session token. Memory only: it is gone when the process ends.
    session: Option<String>,
}

tokio::task_local! {
    /// Set for everything a backup run does: such requests carry the backup header (see the adapter's `lock`).
    pub static BACKUP: bool;
}

fn is_backup() -> bool {
    BACKUP.try_with(|b| *b).unwrap_or(false)
}

fn cfg() -> &'static RwLock<Cfg> {
    static C: OnceLock<RwLock<Cfg>> = OnceLock::new();
    C.get_or_init(|| {
        RwLock::new(Cfg {
            base: String::new(),
            token: String::new(),
            adapter: Arc::new(Adapter::default_profile()),
            adapter_src: String::new(),
            max_upload: 0,
            client: None,
            session: None,
        })
    })
}

fn read_cfg() -> std::sync::RwLockReadGuard<'static, Cfg> {
    cfg().read().unwrap_or_else(|p| p.into_inner())
}

fn write_cfg() -> std::sync::RwLockWriteGuard<'static, Cfg> {
    cfg().write().unwrap_or_else(|p| p.into_inner())
}

/// Snapshot of the settings one request needs.
#[derive(Clone)]
struct Snap {
    base: String,
    token: String,
    adapter: Arc<Adapter>,
    session: Option<String>,
}

fn snapshot() -> Snap {
    let c = read_cfg();
    Snap { base: c.base.clone(), token: c.token.clone(), adapter: c.adapter.clone(), session: c.session.clone() }
}

/// Applies the saved settings. An unparsable stored adapter falls back to the default profile.
pub fn configure(url: &str, token: &str, adapter_text: Option<&str>, max_upload: i64) {
    let mut c = write_cfg();
    let (base, token) = (url.trim().trim_end_matches('/').to_string(), token.trim().to_string());
    if base != c.base || token != c.token {
        c.session = None; // a session only works with the token that created it
    }
    c.base = base;
    c.token = token;
    c.max_upload = max_upload;
    let src = adapter_text.unwrap_or("").to_string();
    if src != c.adapter_src {
        let parsed = match adapter_text {
            Some(t) => Adapter::parse(t).ok(),
            None => None,
        };
        if adapter_text.is_some() && parsed.is_none() {
            logs::warn("Api", "Stored adapter is invalid, using the default profile");
        }
        c.adapter = Arc::new(parsed.unwrap_or_else(Adapter::default_profile));
        c.adapter_src = src;
        c.client = None; // timeouts may have changed
    }
}

pub fn configured() -> bool {
    let c = read_cfg();
    !c.base.is_empty() && !c.token.is_empty()
}

fn ensure_configured() -> Result<()> {
    if configured() {
        Ok(())
    } else {
        Err(Error::io("Server is not configured"))
    }
}

/// Validates and applies a new adapter profile (None restores the default). Takes effect immediately.
pub fn set_adapter(text: Option<&str>) -> Result<()> {
    let parsed = match text {
        Some(t) => Adapter::parse(t)?,
        None => Adapter::default_profile(),
    };
    let mut c = write_cfg();
    c.adapter = Arc::new(parsed);
    c.adapter_src = text.unwrap_or("").to_string();
    c.client = None;
    Ok(())
}

/// (name, pretty JSON) of the active adapter.
pub fn adapter_info() -> (String, String) {
    let a = read_cfg().adapter.clone();
    (a.name.clone(), a.pretty())
}

/// What the Kotlin video player needs to talk to the server itself.
pub fn player_config() -> Value {
    let s = snapshot();
    let mut headers = serde_json::Map::new();
    for (k, v) in &s.adapter.headers {
        headers.insert(k.clone(), Value::String(v.clone()));
    }
    if let Some((k, v)) = s.adapter.auth_header(&s.token) {
        headers.insert(k, Value::String(v));
    }
    if let Some(sess) = &s.session {
        headers.insert(s.adapter.session_header.clone(), Value::String(sess.clone()));
    }
    serde_json::json!({
        "headers": headers,
        "connect": s.adapter.seconds("connect", 15),
        "read": s.adapter.seconds("read", 60),
        "write": s.adapter.seconds("write", 120),
    })
}

/// One shared client: HTTP/2 multiplexing over TLS keeps thumbnail grids cheap.
fn client() -> Result<Client> {
    if let Some(c) = &read_cfg().client {
        return Ok(c.clone());
    }
    let mut c = write_cfg();
    if let Some(existing) = &c.client {
        return Ok(existing.clone());
    }
    let built = Client::builder()
        .use_rustls_tls()
        .https_only(true)
        .connect_timeout(Duration::from_secs(c.adapter.seconds("connect", 15)))
        .pool_idle_timeout(Duration::from_secs(300))
        // There is no per-write timeout in this HTTP stack; keep-alive pings detect dead connections while a
        // request is in flight (idle connections are not pinged, so the radio can sleep).
        .tcp_keepalive(Duration::from_secs(60))
        .http2_keep_alive_interval(Duration::from_secs(30))
        .http2_keep_alive_timeout(Duration::from_secs(20))
        .build()
        .map_err(|e| Error::io(format!("Cannot create the HTTP client: {e}")))?;
    c.client = Some(built.clone());
    Ok(built)
}

fn max_upload() -> i64 {
    read_cfg().max_upload
}

// ---------------------------------------------------------------------------------------------------------------
// Request building

enum BodyKind {
    None,
    Empty,
    Json(String),
}

struct Prepared {
    method: Method,
    url: String,
    body: BodyKind,
}

impl Prepared {
    /// Path only (no query string), for log lines: tokens in the query must never be logged.
    fn log_path(&self) -> String {
        Url::parse(&self.url).map(|u| u.path().to_string()).unwrap_or_default()
    }
}

fn vars(remote: &str, path: &str) -> Vars {
    let mut v = Vars::new();
    v.insert("remote".into(), Var::Str(remote.to_string()));
    v.insert("path".into(), Var::Str(path.to_string()));
    v.insert("id".into(), Var::Str(b64url(format!("{remote}:{path}").as_bytes())));
    v
}

fn parse_base(base: &str) -> Result<Url> {
    let u = Url::parse(base).map_err(|_| Error::io("Invalid server URL"))?;
    if (u.scheme() != "http" && u.scheme() != "https") || u.host_str().is_none() {
        return Err(Error::io("Invalid server URL"));
    }
    Ok(u)
}

/// Full request URL: base + prefix + endpoint path (segments encoded) + query parameters.
fn request_url(snap: &Snap, ep: &Endpoint, vars: &Vars) -> Result<String> {
    let mut root = parse_base(&snap.base)?;
    root.set_query(None);
    root.set_fragment(None);
    let mut segments: Vec<String> =
        root.path().split('/').filter(|s| !s.is_empty()).map(|s| s.to_string()).collect();
    let rel = [snap.adapter.prefix.clone(), adapter::sub(&ep.path, vars, true)]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("/");
    let trailing_slash = rel.ends_with('/') || rel.ends_with('\\');
    for seg in rel.split(|c| c == '/' || c == '\\') {
        match seg {
            "" | "." => {}
            ".." => {
                segments.pop();
            }
            s => segments.push(enc_path_segment(s)),
        }
    }
    let origin = root.origin().ascii_serialization();
    let mut out = origin;
    for s in &segments {
        out.push('/');
        out.push_str(s);
    }
    if trailing_slash {
        out.push('/');
    }
    let mut query: Vec<String> = ep
        .query
        .iter()
        .map(|(k, v)| format!("{}={}", enc_query(k), enc_query(&adapter::sub(v, vars, false))))
        .collect();
    if snap.adapter.auth_type == "query" {
        query.push(format!("{}={}", enc_query(&snap.adapter.auth_name), enc_query(&snap.token)));
    }
    if !query.is_empty() {
        out.push('?');
        out.push_str(&query.join("&"));
    }
    Ok(out)
}

fn prepare(snap: &Snap, key: &str, vars: &Vars) -> Result<Prepared> {
    let ep = snap.adapter.endpoint(key)?;
    let url = request_url(snap, &ep, vars)?;
    let method = Method::from_bytes(ep.method.as_bytes())
        .map_err(|_| Error::io(format!("Invalid HTTP method: {}", ep.method)))?;
    let body = if method == Method::GET || method == Method::HEAD {
        BodyKind::None
    } else {
        match &ep.body {
            Some(tpl) => BodyKind::Json(Value::Object(adapter::fill(tpl, vars)).to_string()),
            None => BodyKind::Empty,
        }
    };
    Ok(Prepared { method, url, body })
}

fn put_header(h: &mut HeaderMap, k: &str, v: &str) -> Result<()> {
    let name = HeaderName::from_bytes(k.as_bytes()).map_err(|_| Error::io(format!("Invalid header name: {k}")))?;
    let mut value =
        HeaderValue::from_str(v).map_err(|_| Error::io(format!("Invalid value for header: {k}")))?;
    if name == AUTHORIZATION {
        value.set_sensitive(true);
    }
    h.insert(name, value); // insert replaces, like OkHttp's header()
    Ok(())
}

/// User agent, then the adapter's extra headers, then the auth header (which wins on a name clash).
fn base_headers(snap: &Snap) -> Result<HeaderMap> {
    let mut h = HeaderMap::new();
    h.insert(USER_AGENT, HeaderValue::from_static(USER_AGENT_VALUE));
    for (k, v) in &snap.adapter.headers {
        put_header(&mut h, k, v)?;
    }
    if let Some((k, v)) = snap.adapter.auth_header(&snap.token) {
        put_header(&mut h, &k, &v)?;
    }
    if let Some(session) = &snap.session {
        put_header(&mut h, &snap.adapter.session_header, session)?;
    }
    if is_backup() {
        if let Some((k, v)) = &snap.adapter.backup_header {
            put_header(&mut h, k, v)?;
        }
    }
    Ok(h)
}

/// The app only talks HTTPS: the token travels with every request.
fn check_https(url: &str) -> Result<()> {
    let u = Url::parse(url).map_err(|_| Error::io("Invalid server URL"))?;
    if u.scheme() != "https" {
        return Err(Error::io(format!(
            "CLEARTEXT communication to {} not permitted by network security policy",
            u.host_str().unwrap_or("")
        )));
    }
    Ok(())
}

fn build(snap: &Snap, p: &Prepared) -> Result<RequestBuilder> {
    let mut rb = client()?.request(p.method.clone(), p.url.as_str()).headers(base_headers(snap)?);
    match &p.body {
        BodyKind::None => {}
        BodyKind::Empty => rb = rb.body(Vec::<u8>::new()),
        BodyKind::Json(s) => {
            rb = rb.header(CONTENT_TYPE, "application/json; charset=utf-8").body(s.clone());
        }
    }
    Ok(rb)
}

// ---------------------------------------------------------------------------------------------------------------
// Sending and reading

/// Readable text for a network failure (the URL, which may carry the token, is dropped).
fn net_error(e: reqwest::Error) -> Error {
    let e = e.without_url();
    if e.is_timeout() {
        return Error::io("timeout");
    }
    let mut root = e.to_string();
    let mut src = std::error::Error::source(&e);
    while let Some(s) = src {
        root = s.to_string();
        src = s.source();
    }
    if e.is_connect() {
        Error::io(format!("Failed to connect: {root}"))
    } else {
        Error::io(root)
    }
}

fn timed_out() -> Error {
    Error::io("timeout")
}

/// Up to `limit` bytes of the body, as lossy text.
async fn read_text_limited(mut resp: Response, limit: usize) -> String {
    let mut out: Vec<u8> = Vec::new();
    while out.len() < limit {
        match timeout(Duration::from_secs(30), resp.chunk()).await {
            Ok(Ok(Some(c))) => out.extend_from_slice(&c),
            _ => break,
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Error for a non-success response: the server's `{"error": "..."}` text when there is one.
async fn fail(resp: Response) -> Error {
    let status = resp.status().as_u16() as i32;
    let raw = take_chars(&read_text_limited(resp, 8192).await, 2000);
    let msg = match serde_json::from_str::<Value>(&raw) {
        Ok(Value::Object(m)) => match m.get("error") {
            Some(Value::String(s)) => s.clone(),
            Some(other) => other.to_string(),
            None => raw.clone(),
        },
        _ => raw.clone(),
    };
    let text = if status == 401 {
        "Unauthorized (check the token)".to_string()
    } else if msg.trim().is_empty() {
        format!("HTTP {status}")
    } else {
        msg
    };
    Error::Api { status, message: take_chars(&text, 240) }
}

/// Sends a prepared request and waits `read` for the response headers. Non-2xx becomes an error.
async fn send_plain(snap: &Snap, p: &Prepared, read: Duration) -> Result<Response> {
    check_https(&p.url)?;
    let rb = build(snap, p)?;
    let resp = timeout(read, rb.send()).await.map_err(|_| timed_out())?.map_err(net_error)?;
    if !resp.status().is_success() {
        return Err(fail(resp).await);
    }
    Ok(resp)
}

/// Reads a whole body; `read` limits the wait for each chunk and `max` the total size (None = unlimited).
async fn read_body(mut resp: Response, read: Duration, max: Option<usize>) -> Result<Vec<u8>> {
    let mut out: Vec<u8> = Vec::new();
    loop {
        match timeout(read, resp.chunk()).await {
            Err(_) => return Err(timed_out()),
            Ok(Err(e)) => return Err(net_error(e)),
            Ok(Ok(None)) => break,
            Ok(Ok(Some(c))) => {
                out.extend_from_slice(&c);
                if let Some(m) = max {
                    if out.len() > m {
                        return Err(Error::io("Response too large"));
                    }
                }
            }
        }
    }
    Ok(out)
}

/// Logs a failure (except cancellations) like the old client did.
async fn traced<T, F>(p: &Prepared, fut: F) -> Result<T>
where
    F: Future<Output = Result<T>>,
{
    let r = fut.await;
    if let Err(e) = &r {
        if !e.is_canceled() {
            logs::warn("Api", &format!("{} {} failed: {}: {}", p.method, p.log_path(), e.describe(), e.message()));
        }
    }
    r
}

async fn call_json(key: &str, vars: Vars, tag: Option<&str>) -> Result<Value> {
    ensure_configured()?;
    let snap = snapshot();
    let p = prepare(&snap, key, &vars)?;
    let read = Duration::from_secs(snap.adapter.seconds("read", 60));
    traced(
        &p,
        with_cancel(tag, async {
            let resp = send_plain(&snap, &p, read).await?;
            let bytes = read_body(resp, read, None).await?;
            let v: Value = serde_json::from_slice(&bytes)
                .map_err(|e| Error::io(format!("Invalid server response: {e}")))?;
            Ok::<Value, Error>(v)
        }),
    )
    .await
}

async fn call_empty(key: &str, vars: Vars, tag: Option<&str>) -> Result<()> {
    ensure_configured()?;
    let snap = snapshot();
    let p = prepare(&snap, key, &vars)?;
    let read = Duration::from_secs(snap.adapter.seconds("read", 60));
    traced(
        &p,
        with_cancel(tag, async {
            let _resp = send_plain(&snap, &p, read).await?;
            Ok::<(), Error>(())
        }),
    )
    .await
}

fn names(v: &Value, key: &str) -> Vec<String> {
    match v.get(key) {
        Some(Value::Array(a)) => a.iter().map(|x| x.as_str().map(|s| s.to_string()).unwrap_or_else(|| x.to_string())).collect(),
        _ => Vec::new(),
    }
}

fn int_of(v: &Value) -> i64 {
    match v {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        Value::String(s) => s.trim().parse::<i64>().unwrap_or(0),
        _ => 0,
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Setup

pub async fn settings() -> Result<Value> {
    call_json("settings", Vars::new(), None).await
}

/// Remote names plus their lock state.
pub struct RemoteInfo {
    pub remotes: Vec<String>,
    /// Remotes that need a PIN.
    pub locked: Vec<String>,
    /// Locked remotes that this session has open right now.
    pub unlocked: Vec<String>,
}

pub async fn remote_info() -> Result<RemoteInfo> {
    let a = snapshot().adapter;
    let v = call_json("remotes", Vars::new(), None).await?;
    Ok(RemoteInfo {
        remotes: names(&v, &a.field("remotesList", "remotes")),
        locked: names(&v, &a.field("remotesLocked", "locked")),
        unlocked: names(&v, &a.field("remotesUnlocked", "unlocked")),
    })
}

/// Opens a locked remote with its PIN. `ttl_secs` is the idle time after which the server locks it again
/// (0 = the server's maximum). The returned session is kept in memory and sent with every request.
pub async fn unlock(remote: &str, pin: &str, ttl_secs: i64) -> Result<()> {
    let mut v = vars(remote, "");
    v.insert("pin".into(), Var::Str(pin.to_string()));
    v.insert("ttl".into(), Var::Num(ttl_secs.max(0)));
    let reply = call_json("unlock", v, None).await?;
    let key = snapshot().adapter.field("unlockSession", "session");
    let session = reply
        .get(&key)
        .and_then(Value::as_str)
        .ok_or_else(|| Error::io("Unexpected unlock response"))?
        .to_string();
    write_cfg().session = Some(session);
    crate::thumbs::forget_failures(); // thumbnails that failed while the remote was locked may work now
    Ok(())
}

/// Locks one remote again, or all of them when `remote` is None. Requests already running are not affected.
pub async fn lock(remote: Option<&str>) -> Result<()> {
    call_empty("lock", vars(remote.unwrap_or(""), ""), None).await?;
    if remote.is_none() {
        write_cfg().session = None;
    }
    Ok(())
}

/// Result of a successful connection test.
pub struct Connected {
    pub max_upload: i64,
    pub remotes: Vec<String>,
    pub locked: Vec<String>,
}

/// Switches to `url` + `token` only if the server answers the settings endpoint; otherwise the previous
/// settings stay in place.
pub async fn try_configure(url: &str, token: &str) -> Result<Connected> {
    let (old_base, old_token, old_session) = {
        let c = read_cfg();
        (c.base.clone(), c.token.clone(), c.session.clone())
    };
    {
        let mut c = write_cfg();
        c.base = url.trim().trim_end_matches('/').to_string();
        c.token = token.trim().to_string();
        if c.base != old_base || c.token != old_token {
            c.session = None;
        }
    }
    match settings().await {
        Ok(s) => {
            let a = snapshot().adapter;
            let max_upload = s.get(a.field("settingsMaxUpload", "maxUploadBytes")).map(int_of).unwrap_or(0);
            let remotes = names(&s, &a.field("settingsRemotes", "remotes"));
            let locked = names(&s, &a.field("settingsLocked", "locked"));
            write_cfg().max_upload = max_upload;
            Ok(Connected { max_upload, remotes, locked })
        }
        Err(e) => {
            let mut c = write_cfg();
            c.base = old_base;
            c.token = old_token;
            c.session = old_session;
            Err(e)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Browse

fn parse_time(s: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(s).map(|d| d.timestamp_millis()).unwrap_or(0)
}

fn parse_entries(v: &Value, a: &Adapter) -> Result<Vec<Entry>> {
    let root = v.as_object().ok_or_else(|| Error::io("Unexpected server response"))?;
    let items = a.field("listItems", "items");
    let (f_name, f_size, f_dir, f_time) =
        (a.field("name", "name"), a.field("size", "size"), a.field("isDir", "isDir"), a.field("modTime", "modTime"));
    let fmt = a.field("timeFormat", "iso");
    let mut out = Vec::new();
    if let Some(Value::Array(arr)) = root.get(&items) {
        for item in arr {
            let o = match item.as_object() {
                Some(o) => o,
                None => continue,
            };
            let (mut name, mut size, mut dir, mut modified) = (String::new(), 0i64, false, 0i64);
            for (k, val) in o {
                if val.is_null() {
                    continue;
                }
                if *k == f_name {
                    name = match val {
                        Value::String(s) => s.clone(),
                        other => other.to_string(),
                    };
                } else if *k == f_size {
                    size = int_of(val);
                } else if *k == f_dir {
                    dir = val.as_bool().unwrap_or(false);
                } else if *k == f_time {
                    modified = match fmt.as_str() {
                        "epochMs" => int_of(val),
                        "epochSec" => int_of(val) * 1000,
                        _ => val.as_str().map(parse_time).unwrap_or(0),
                    };
                }
            }
            out.push(Entry { name, size: if dir { 0 } else { size.max(0) }, is_dir: dir, modified });
        }
    }
    Ok(out)
}

/// Folder listing; also written to the disk cache so the next visit shows it instantly.
pub async fn list(remote: &str, path: &str) -> Result<Vec<Entry>> {
    let adapter = snapshot().adapter;
    let v = call_json("list", vars(remote, path), None).await?;
    let entries = parse_entries(&v, &adapter)?;
    let _ = cache::put_list(remote, path, &entries);
    Ok(entries)
}

/// URL for players. The auth header is added by the caller (or is part of the URL for query auth).
pub fn stream_url(remote: &str, path: &str) -> Result<String> {
    let snap = snapshot();
    let ep = snap.adapter.endpoint("stream")?;
    request_url(&snap, &ep, &vars(remote, path))
}

/// Server-generated thumbnail bytes. `None` = not supported for this file type (or too large).
pub async fn thumbnail(remote: &str, path: &str, tag: &str) -> Result<Option<Vec<u8>>> {
    ensure_configured()?;
    let snap = snapshot();
    let p = prepare(&snap, "thumbnail", &vars(remote, path))?;
    let read = Duration::from_secs(snap.adapter.seconds("thumbnail", 120));
    let r = with_cancel(Some(tag), async {
        check_https(&p.url)?;
        let rb = build(&snap, &p)?;
        let resp = timeout(read, rb.send()).await.map_err(|_| timed_out())?.map_err(net_error)?;
        if resp.status().as_u16() == 415 {
            return Ok(None);
        }
        if !resp.status().is_success() {
            return Err(fail(resp).await);
        }
        if let Some(len) = resp.content_length() {
            if len > 2 * 1024 * 1024 {
                return Ok(None);
            }
        }
        let bytes = read_body(resp, read, Some(16 * 1024 * 1024)).await?;
        Ok::<Option<Vec<u8>>, Error>(Some(bytes))
    })
    .await;
    if let Err(e) = &r {
        if !e.is_canceled() {
            logs::warn("Api", &format!("thumbnail {path} failed: {}: {}", e.describe(), e.message()));
        }
    }
    r
}

/// Streams a remote file to `dest` (the caller renames a `.part` file into place).
pub async fn download(remote: &str, path: &str, dest: &Path, tag: &str, on_bytes: Option<ByteCb>) -> Result<()> {
    ensure_configured()?;
    let snap = snapshot();
    let p = prepare(&snap, "stream", &vars(remote, path))?;
    let read = Duration::from_secs(snap.adapter.seconds("read", 60));
    traced(
        &p,
        with_cancel(Some(tag), async {
            let mut resp = send_plain(&snap, &p, read).await?;
            let mut file = tokio::fs::File::create(dest).await?;
            loop {
                match timeout(read, resp.chunk()).await {
                    Err(_) => return Err(timed_out()),
                    Ok(Err(e)) => return Err(net_error(e)),
                    Ok(Ok(None)) => break,
                    Ok(Ok(Some(c))) => {
                        file.write_all(&c).await?;
                        if let Some(cb) = &on_bytes {
                            cb(c.len() as i64);
                        }
                    }
                }
            }
            file.flush().await?;
            Ok::<(), Error>(())
        }),
    )
    .await
}

// ---------------------------------------------------------------------------------------------------------------
// Operations

pub async fn mkdir(remote: &str, path: &str, tag: Option<&str>) -> Result<()> {
    call_empty("mkdir", vars(remote, path), tag).await
}

pub async fn rename(remote: &str, path: &str, name: &str, tag: Option<&str>) -> Result<()> {
    let mut v = vars(remote, path);
    v.insert("name".into(), Var::Str(name.to_string()));
    call_empty("rename", v, tag).await
}

pub async fn delete(remote: &str, path: &str, is_dir: bool, tag: Option<&str>) -> Result<()> {
    let mut v = vars(remote, path);
    v.insert("isDir".into(), Var::Bool(is_dir));
    call_empty("delete", v, tag).await
}

fn with_dest(remote: &str, path: &str, dest_remote: &str, dest: &str) -> Vars {
    let mut v = vars(remote, path);
    v.insert("destRemote".into(), Var::Str(dest_remote.to_string()));
    v.insert("dest".into(), Var::Str(dest.to_string()));
    v
}

pub async fn copy(remote: &str, path: &str, dest_remote: &str, dest: &str, tag: Option<&str>) -> Result<()> {
    call_empty("copy", with_dest(remote, path, dest_remote, dest), tag).await
}

pub async fn move_(remote: &str, path: &str, dest_remote: &str, dest: &str, tag: Option<&str>) -> Result<()> {
    call_empty("move", with_dest(remote, path, dest_remote, dest), tag).await
}

/// Streams a local file as the request body (never buffered in memory) and keeps its modification time.
pub async fn upload(remote: &str, path: &str, file: &Path, tag: &str, on_bytes: Option<ByteCb>) -> Result<()> {
    ensure_configured()?;
    let snap = snapshot();
    let md = tokio::fs::metadata(file).await.map_err(|e| Error::io(format!("{}: {e}", file.display())))?;
    let len = md.len();
    let max = max_upload();
    if max > 0 && len as i64 > max {
        return Err(Error::io(format!("Larger than the server limit ({} MB)", max / 1_048_576)));
    }
    let mut v = vars(remote, path);
    v.insert("modtime".into(), Var::Num(mtime_ms(&md)));
    let p = prepare(&snap, "upload", &v)?;
    let write_secs = snap.adapter.seconds("write", 120) as i64;
    let read_secs = snap.adapter.seconds("upload", 900) as i64;

    traced(
        &p,
        with_cancel(Some(tag), async {
            check_https(&p.url)?;
            let f = tokio::fs::File::open(file).await?;

            // `activity` is refreshed whenever the HTTP stack takes more of the body. If it stops for longer than
            // the write timeout the connection is stuck; once the body is fully sent, the same clock enforces the
            // (longer) upload timeout while the server finishes processing.
            let activity = Arc::new(AtomicI64::new(now_ms()));
            let done = Arc::new(AtomicBool::new(len == 0));
            let sent = Arc::new(AtomicI64::new(0));
            let (act2, done2, sent2, cb) = (activity.clone(), done.clone(), sent.clone(), on_bytes.clone());
            let stream = ReaderStream::with_capacity(f, 64 * 1024).inspect(move |r| {
                if let Ok(chunk) = r {
                    let n = chunk.len() as i64;
                    if let Some(cb) = &cb {
                        cb(n);
                    }
                    let total = sent2.fetch_add(n, Ordering::Relaxed) + n;
                    if total >= len as i64 {
                        done2.store(true, Ordering::Relaxed);
                    }
                    act2.store(now_ms(), Ordering::Relaxed);
                }
            });

            let mut headers = base_headers(&snap)?;
            headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/octet-stream"));
            headers.insert(CONTENT_LENGTH, HeaderValue::from(len));
            let rb = client()?
                .request(p.method.clone(), p.url.as_str())
                .headers(headers)
                .body(reqwest::Body::wrap_stream(stream));

            let send = rb.send();
            tokio::pin!(send);
            let resp = loop {
                tokio::select! {
                    r = &mut send => break r.map_err(net_error)?,
                    _ = tokio::time::sleep(Duration::from_secs(1)) => {
                        let idle = now_ms() - activity.load(Ordering::Relaxed);
                        let limit = if done.load(Ordering::Relaxed) { read_secs } else { write_secs };
                        if idle > limit * 1000 {
                            return Err(timed_out());
                        }
                    }
                }
            };
            if !resp.status().is_success() {
                return Err(fail(resp).await);
            }
            Ok::<(), Error>(())
        }),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snap(base: &str, token: &str) -> Snap {
        Snap { base: base.to_string(), token: token.to_string(), adapter: Arc::new(Adapter::default_profile()), session: None }
    }

    #[test]
    fn list_url() {
        let s = snap("https://example.com", "tok");
        let ep = s.adapter.endpoint("list").unwrap();
        let url = request_url(&s, &ep, &vars("mega", "My Photos/a+b.jpg")).unwrap();
        assert_eq!(url, "https://example.com/api/v1/files?remote=mega&path=My%20Photos%2Fa+b.jpg");
    }

    #[test]
    fn base_with_path_and_thumbnail_id() {
        let s = snap("https://example.com/rc", "tok");
        let ep = s.adapter.endpoint("thumbnail").unwrap();
        let url = request_url(&s, &ep, &vars("a", "b")).unwrap();
        assert_eq!(url, format!("https://example.com/rc/api/v1/thumbnails/{}", b64url(b"a:b")));
    }

    #[test]
    fn query_auth_appends_the_token() {
        let mut s = snap("https://example.com", "tok");
        s.adapter = Arc::new(
            Adapter::parse(&adapter::DEFAULT_JSON.replace(r#""auth": { "type": "bearer" }"#, r#""auth": { "type": "query" }"#))
                .unwrap(),
        );
        let ep = s.adapter.endpoint("remotes").unwrap();
        assert_eq!(request_url(&s, &ep, &Vars::new()).unwrap(), "https://example.com/api/v1/remotes?token=tok");
        assert!(s.adapter.auth_header("tok").is_none());
    }

    #[test]
    fn prepared_delete_has_a_typed_json_body() {
        let s = snap("https://example.com", "tok");
        let mut v = vars("r", "p");
        v.insert("isDir".into(), Var::Bool(false));
        let p = prepare(&s, "delete", &v).unwrap();
        assert_eq!(p.method, Method::POST);
        match p.body {
            BodyKind::Json(j) => assert_eq!(j, r#"{"remote":"r","path":"p","isDir":false}"#),
            _ => panic!("expected a JSON body"),
        }
    }

    #[test]
    fn plain_http_is_refused() {
        assert!(check_https("http://example.com/x").is_err());
        assert!(check_https("https://example.com/x").is_ok());
    }

    #[test]
    fn entries_follow_the_adapter_fields() {
        let a = Adapter::default_profile();
        let v = serde_json::json!({ "items": [
            { "name": "a.jpg", "size": 5, "isDir": false, "modTime": "1970-01-01T00:00:01Z" },
            { "name": "d", "size": 9, "isDir": true, "modTime": null }
        ]});
        let e = parse_entries(&v, &a).unwrap();
        assert_eq!(e.len(), 2);
        assert_eq!((e[0].size, e[0].modified), (5, 1000));
        assert!(e[1].is_dir && e[1].size == 0 && e[1].modified == 0);
    }
}
