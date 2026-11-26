//! Connection adapter: a JSON profile that tells the client HOW to talk to the API (URL prefix, auth scheme,
//! endpoint paths / methods / parameters, response field names, timeouts). It is edited in the app at runtime,
//! so an API change does not need a new APK.
//!
//! Placeholders: {remote} {path} {name} {dest} {destRemote} {isDir} {modtime} {id}

use std::collections::HashMap;
use std::sync::OnceLock;

use regex::Regex;
use serde_json::{Map, Value};

use crate::error::{Error, Result};
use crate::util::android_uri_encode;

/// Values substituted into placeholders.
#[derive(Clone, Debug)]
pub enum Var {
    Str(String),
    Bool(bool),
    Num(i64),
}

impl Var {
    fn text(&self) -> String {
        match self {
            Var::Str(s) => s.clone(),
            Var::Bool(b) => b.to_string(),
            Var::Num(n) => n.to_string(),
        }
    }
}

pub type Vars = HashMap<String, Var>;

pub struct Endpoint {
    pub method: String,
    pub path: String,
    pub query: Vec<(String, String)>,
    pub body: Option<Map<String, Value>>,
}

#[derive(Clone, Debug)]
pub struct Adapter {
    raw: Value,
    pub name: String,
    pub prefix: String,
    pub auth_type: String, // bearer | header | query
    pub auth_name: String,
    pub auth_prefix: String,
    pub headers: Vec<(String, String)>,
    /// Header that carries the remote-lock session token.
    pub session_header: String,
    /// Header (name, value) that marks scheduled backups, which may run on locked remotes without a PIN.
    pub backup_header: Option<(String, String)>,
    timeouts: Map<String, Value>,
    fields: Map<String, Value>,
    endpoints: Map<String, Value>,
}

const REQUIRED: [&str; 11] =
    ["remotes", "settings", "list", "stream", "thumbnail", "mkdir", "rename", "delete", "copy", "move", "upload"];

/// Endpoints that older saved profiles may lack; the built-in definition is used for them.
const OPTIONAL: [&str; 2] = ["unlock", "lock"];

/// The built-in profile for the RCAPI server.
pub const DEFAULT_JSON: &str = r#"{
  "name": "rcapi-v1",
  "prefix": "api/v1",
  "auth": { "type": "bearer" },
  "headers": {},
  "lock": { "header": "X-Remote-Session", "backupHeader": "X-Rcm-Backup", "backupValue": "1" },
  "timeouts": { "connect": 15, "read": 60, "write": 120, "upload": 900, "thumbnail": 120 },
  "fields": {
    "remotesList": "remotes",
    "remotesLocked": "locked",
    "remotesUnlocked": "unlocked",
    "settingsRemotes": "remotes",
    "settingsLocked": "locked",
    "unlockSession": "session",
    "settingsMaxUpload": "maxUploadBytes",
    "listItems": "items",
    "name": "name",
    "size": "size",
    "isDir": "isDir",
    "modTime": "modTime",
    "timeFormat": "iso"
  },
  "endpoints": {
    "remotes":   { "path": "remotes" },
    "settings":  { "path": "server/settings" },
    "list":      { "path": "files", "query": { "remote": "{remote}", "path": "{path}" } },
    "stream":    { "path": "stream", "query": { "remote": "{remote}", "path": "{path}" } },
    "thumbnail": { "path": "thumbnails/{id}" },
    "mkdir":     { "method": "POST", "path": "operations/mkdir",  "body": { "remote": "{remote}", "path": "{path}" } },
    "rename":    { "method": "POST", "path": "operations/rename", "body": { "remote": "{remote}", "path": "{path}", "name": "{name}" } },
    "delete":    { "method": "POST", "path": "operations/delete", "body": { "remote": "{remote}", "path": "{path}", "isDir": "{isDir}" } },
    "copy":      { "method": "POST", "path": "operations/copy",   "body": { "remote": "{remote}", "path": "{path}", "destRemote": "{destRemote}", "dest": "{dest}" } },
    "move":      { "method": "POST", "path": "operations/move",   "body": { "remote": "{remote}", "path": "{path}", "destRemote": "{destRemote}", "dest": "{dest}" } },
    "upload":    { "method": "PUT",  "path": "operations/upload", "query": { "remote": "{remote}", "path": "{path}", "modtime": "{modtime}" } },
    "unlock":    { "method": "POST", "path": "remotes/unlock", "body": { "remote": "{remote}", "pin": "{pin}", "ttl": "{ttl}" } },
    "lock":      { "method": "POST", "path": "remotes/lock",   "body": { "remote": "{remote}" } }
  }
}"#;

/// JSON value as text, the way `org.json` coerces it (numbers and booleans become their text).
fn text_of(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

fn opt_string(o: &Map<String, Value>, key: &str, default: &str) -> String {
    match o.get(key) {
        None | Some(Value::Null) => default.to_string(),
        Some(v) => text_of(v),
    }
}

fn object_or_empty(o: &Map<String, Value>, key: &str) -> Map<String, Value> {
    o.get(key).and_then(Value::as_object).cloned().unwrap_or_default()
}

impl Adapter {
    /// Validates and parses a profile. Error messages are shown to the user in the adapter editor.
    pub fn parse(text: &str) -> Result<Adapter> {
        let raw: Value =
            serde_json::from_str(text).map_err(|e| Error::Adapter(format!("Invalid JSON: {e}")))?;
        let obj = raw
            .as_object()
            .ok_or_else(|| Error::Adapter("Invalid JSON: expected an object".to_string()))?;
        let eps = obj
            .get("endpoints")
            .and_then(Value::as_object)
            .ok_or_else(|| Error::Adapter("Missing \"endpoints\"".to_string()))?;
        for k in REQUIRED {
            let e = eps
                .get(k)
                .and_then(Value::as_object)
                .ok_or_else(|| Error::Adapter(format!("Missing endpoint \"{k}\"")))?;
            if !e.contains_key("path") {
                return Err(Error::Adapter(format!("Endpoint \"{k}\" has no path")));
            }
        }

        let auth = object_or_empty(obj, "auth");
        let auth_type = opt_string(&auth, "type", "bearer");
        let default_auth_name = if auth_type == "query" { "token" } else { "Authorization" };
        let headers: Vec<(String, String)> = obj
            .get("headers")
            .and_then(Value::as_object)
            .map(|h| h.iter().map(|(k, v)| (k.clone(), text_of(v))).collect())
            .unwrap_or_default();

        let lock = object_or_empty(obj, "lock");
        let backup_name = opt_string(&lock, "backupHeader", "X-Rcm-Backup");
        let backup_header =
            if backup_name.is_empty() { None } else { Some((backup_name, opt_string(&lock, "backupValue", "1"))) };

        let adapter = Adapter {
            session_header: opt_string(&lock, "header", "X-Remote-Session"),
            backup_header,
            name: opt_string(obj, "name", "custom"),
            prefix: opt_string(obj, "prefix", "api/v1").trim_matches('/').to_string(),
            auth_name: opt_string(&auth, "name", default_auth_name),
            auth_prefix: opt_string(&auth, "prefix", ""),
            auth_type,
            headers,
            timeouts: object_or_empty(obj, "timeouts"),
            fields: object_or_empty(obj, "fields"),
            endpoints: eps.clone(),
            raw: Value::Null,
        };
        Ok(Adapter { raw, ..adapter })
    }

    pub fn default_profile() -> Adapter {
        Adapter::parse(DEFAULT_JSON).expect("the built-in adapter is valid")
    }

    /// The profile as pretty JSON (what the editor shows).
    pub fn pretty(&self) -> String {
        serde_json::to_string_pretty(&self.raw).unwrap_or_default()
    }

    pub fn field(&self, key: &str, default: &str) -> String {
        opt_string(&self.fields, key, default)
    }

    /// A timeout in seconds (at least 1).
    pub fn seconds(&self, key: &str, default: i64) -> u64 {
        let v = match self.timeouts.get(key) {
            Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(default),
            Some(Value::String(s)) => s.trim().parse::<i64>().unwrap_or(default),
            _ => default,
        };
        v.max(1) as u64
    }

    /// Header (name, value) sent with every request, or None when the token goes in the query string.
    pub fn auth_header(&self, token: &str) -> Option<(String, String)> {
        match self.auth_type.as_str() {
            "query" => None,
            "header" => Some((self.auth_name.clone(), format!("{}{}", self.auth_prefix, token))),
            _ => Some(("Authorization".to_string(), format!("Bearer {token}"))),
        }
    }

    pub fn endpoint(&self, key: &str) -> Result<Endpoint> {
        // Locking endpoints are optional so adapters saved before locking existed keep working.
        let fallback = if OPTIONAL.contains(&key) { default_endpoints().get(key) } else { None };
        let e = self
            .endpoints
            .get(key)
            .or(fallback)
            .and_then(Value::as_object)
            .ok_or_else(|| Error::Adapter(format!("Adapter has no endpoint \"{key}\"")))?;
        let path = e
            .get("path")
            .map(text_of)
            .ok_or_else(|| Error::Adapter(format!("Endpoint \"{key}\" has no path")))?;
        let query: Vec<(String, String)> = e
            .get("query")
            .and_then(Value::as_object)
            .map(|q| q.iter().map(|(k, v)| (k.clone(), text_of(v))).collect())
            .unwrap_or_default();
        Ok(Endpoint {
            method: opt_string(e, "method", "GET").to_uppercase(),
            path: path.trim_start_matches('/').to_string(),
            query,
            body: e.get("body").and_then(Value::as_object).cloned(),
        })
    }
}

fn default_endpoints() -> &'static Map<String, Value> {
    static E: OnceLock<Map<String, Value>> = OnceLock::new();
    E.get_or_init(|| {
        serde_json::from_str::<Value>(DEFAULT_JSON)
            .ok()
            .and_then(|v| v.get("endpoints").and_then(Value::as_object).cloned())
            .unwrap_or_default()
    })
}

fn var_regex() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"\{([A-Za-z0-9_]+)\}").expect("valid regex"))
}

/// Replaces `{name}` placeholders; `encode` percent-encodes the values (used for URL paths).
pub fn sub(s: &str, vars: &Vars, encode: bool) -> String {
    var_regex()
        .replace_all(s, |caps: &regex::Captures| {
            let x = vars.get(&caps[1]).map(Var::text).unwrap_or_default();
            if encode {
                android_uri_encode(&x)
            } else {
                x
            }
        })
        .into_owned()
}

/// The variable name when `s` is exactly `{name}`.
fn exact_var(s: &str) -> Option<&str> {
    let inner = s.strip_prefix('{')?.strip_suffix('}')?;
    if !inner.is_empty() && inner.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') {
        Some(inner)
    } else {
        None
    }
}

/// A value that is exactly "{var}" keeps the variable's type (boolean / number) inside JSON bodies.
fn typed(s: &str, vars: &Vars) -> Value {
    if let Some(name) = exact_var(s) {
        match vars.get(name) {
            Some(Var::Bool(b)) => return Value::Bool(*b),
            Some(Var::Num(n)) => return Value::from(*n),
            _ => {}
        }
    }
    Value::String(sub(s, vars, false))
}

/// Fills the placeholders of a JSON body template.
pub fn fill(o: &Map<String, Value>, vars: &Vars) -> Map<String, Value> {
    let mut out = Map::new();
    for (k, v) in o {
        let nv = match v {
            Value::String(s) => typed(s, vars),
            Value::Object(inner) => Value::Object(fill(inner, vars)),
            other => other.clone(),
        };
        out.insert(k.clone(), nv);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vars() -> Vars {
        let mut v = Vars::new();
        v.insert("remote".into(), Var::Str("mega".into()));
        v.insert("path".into(), Var::Str("a b/c.jpg".into()));
        v.insert("isDir".into(), Var::Bool(true));
        v.insert("modtime".into(), Var::Num(42));
        v
    }

    #[test]
    fn default_profile_is_valid() {
        let a = Adapter::default_profile();
        assert_eq!(a.name, "rcapi-v1");
        assert_eq!(a.prefix, "api/v1");
        assert_eq!(a.seconds("read", 1), 60);
        assert_eq!(a.endpoint("upload").unwrap().method, "PUT");
        assert_eq!(a.auth_header("t").unwrap(), ("Authorization".to_string(), "Bearer t".to_string()));
    }

    #[test]
    fn old_profiles_get_the_lock_endpoints() {
        let mut v: Value = serde_json::from_str(DEFAULT_JSON).unwrap();
        let eps = v["endpoints"].as_object_mut().unwrap();
        eps.remove("unlock");
        eps.remove("lock");
        v.as_object_mut().unwrap().remove("lock");
        let old = v.to_string();
        let a = Adapter::parse(&old).unwrap();
        assert_eq!(a.endpoint("unlock").unwrap().path, "remotes/unlock");
        assert_eq!(a.endpoint("lock").unwrap().method, "POST");
        assert_eq!(a.session_header, "X-Remote-Session");
        assert_eq!(a.backup_header, Some(("X-Rcm-Backup".to_string(), "1".to_string())));
    }

    #[test]
    fn rejects_incomplete_profiles() {
        assert!(Adapter::parse("not json").is_err());
        assert!(Adapter::parse("{}").is_err());
        assert!(Adapter::parse(r#"{"endpoints":{}}"#).is_err());
    }

    #[test]
    fn substitution() {
        assert_eq!(sub("{remote}:{path}", &vars(), false), "mega:a b/c.jpg");
        assert_eq!(sub("{path}", &vars(), true), "a%20b%2Fc.jpg");
        assert_eq!(sub("{unknown}", &vars(), false), "");
    }

    #[test]
    fn json_bodies_keep_types() {
        let tpl = serde_json::json!({ "p": "{path}", "d": "{isDir}", "n": "{modtime}", "x": { "r": "{remote}" } });
        let out = fill(tpl.as_object().unwrap(), &vars());
        assert_eq!(out["d"], Value::Bool(true));
        assert_eq!(out["n"], Value::from(42));
        assert_eq!(out["p"], Value::String("a b/c.jpg".into()));
        assert_eq!(out["x"]["r"], Value::String("mega".into()));
    }

    #[test]
    fn key_order_is_preserved() {
        let a = Adapter::default_profile();
        let pretty = a.pretty();
        assert!(pretty.find("\"name\"").unwrap() < pretty.find("\"prefix\"").unwrap());
    }
}
