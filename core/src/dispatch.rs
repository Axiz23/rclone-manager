//! Method router: the single entry point Kotlin calls through JNI (`Core.run(method, args)`).
//! Arguments and results are JSON, so the native interface stays one function and the Kotlin side is easy to extend.

use std::path::Path;
use std::sync::Arc;

use serde_json::{json, Value};

use crate::error::{Error, Result};
use crate::jobs::Job;
use crate::model::{ByteCb, Sink};
use crate::ops::Loc;
use crate::state::block_on;
use crate::{api, backup, cache, cancel, jobs, logs, ops, state, thumbs};

fn s(a: &Value, k: &str) -> String {
    a.get(k).and_then(Value::as_str).unwrap_or("").to_string()
}

/// A string argument that may be null / absent.
fn os(a: &Value, k: &str) -> Option<String> {
    match a.get(k) {
        Some(Value::String(x)) if !x.is_empty() => Some(x.clone()),
        _ => None,
    }
}

fn i(a: &Value, k: &str) -> i64 {
    a.get(k).and_then(Value::as_i64).unwrap_or(0)
}

fn b(a: &Value, k: &str) -> bool {
    a.get(k).and_then(Value::as_bool).unwrap_or(false)
}

fn bytes_cb(events: &Sink) -> Option<ByteCb> {
    events.clone().map(|e| -> ByteCb { Arc::new(move |n| e.on_bytes(n)) })
}

fn to_value<T: serde::Serialize>(v: &T) -> Result<Value> {
    Ok(serde_json::to_value(v)?)
}

pub fn dispatch(method: &str, args: &str, events: Sink) -> Result<Value> {
    let a: Value = if args.trim().is_empty() { Value::Null } else { serde_json::from_str(args)? };
    match method {
        // ---- lifecycle and configuration
        "init" => {
            state::set_paths(&s(&a, "files"), &s(&a, "cache"));
            state::set_tz_offset(i(&a, "tz") as i32);
            crate::install_once();
            Ok(Value::Null)
        }
        "configure" => {
            state::set_tz_offset(i(&a, "tz") as i32);
            api::configure(&s(&a, "url"), &s(&a, "token"), os(&a, "adapter").as_deref(), i(&a, "maxUpload"));
            Ok(Value::Null)
        }
        "configured" => Ok(json!(api::configured())),
        "cancel" => {
            cancel::cancel(&s(&a, "tag"));
            Ok(Value::Null)
        }
        "dirs" => Ok(json!({
            "lists": cache::lists_dir()?.to_string_lossy(),
            "files": cache::files_dir()?.to_string_lossy(),
            "thumbs": cache::thumbs_dir()?.to_string_lossy(),
        })),

        // ---- adapter and server setup
        "adapter.set" => {
            api::set_adapter(os(&a, "text").as_deref())?;
            Ok(Value::Null)
        }
        "adapter.info" => {
            let (name, pretty) = api::adapter_info();
            Ok(json!({ "name": name, "json": pretty }))
        }
        "server.connect" => {
            let c = block_on(api::try_configure(&s(&a, "url"), &s(&a, "token")))?;
            Ok(json!({ "maxUpload": c.max_upload, "remotes": c.remotes }))
        }
        "player" => Ok(api::player_config()),

        // ---- browsing
        "remotes" => Ok(json!(block_on(api::remotes())?)),
        "list" => to_value(&block_on(api::list(&s(&a, "remote"), &s(&a, "path")))?),
        "streamUrl" => Ok(json!(api::stream_url(&s(&a, "remote"), &s(&a, "path"))?)),
        "download" => {
            let dest = s(&a, "dest");
            block_on(api::download(&s(&a, "remote"), &s(&a, "path"), Path::new(&dest), &s(&a, "tag"), bytes_cb(&events)))?;
            Ok(Value::Null)
        }

        // ---- caches
        "cache.list" => match cache::get_list(&s(&a, "remote"), &s(&a, "path")) {
            Some(l) => to_value(&l),
            None => Ok(Value::Null),
        },
        "cache.photo" => {
            let p = cache::photo_path(&s(&a, "remote"), &s(&a, "path"), i(&a, "size"), i(&a, "modified"))?;
            Ok(json!(p.to_string_lossy()))
        }
        "cache.trim" => {
            cache::trim_files(i(&a, "limit"))?;
            Ok(Value::Null)
        }
        "cache.size" => Ok(json!(cache::size()?)),
        "cache.clear" => {
            cache::clear()?;
            Ok(Value::Null)
        }
        "thumb.fetch" => {
            let r = block_on(thumbs::fetch(&s(&a, "remote"), &s(&a, "path"), &s(&a, "key"), &s(&a, "tag")))?;
            Ok(match r {
                Some(p) => json!(p.to_string_lossy()),
                None => Value::Null,
            })
        }
        "thumb.wrote" => {
            thumbs::wrote();
            Ok(Value::Null)
        }
        "thumb.size" => Ok(json!(thumbs::size()?)),
        "thumb.clear" => {
            thumbs::clear()?;
            Ok(Value::Null)
        }

        // ---- file operations
        "ops.mkdir" => {
            let dir = Loc { remote: os(&a, "remote"), path: s(&a, "dir") };
            block_on(ops::mkdir(&dir, &s(&a, "name")))?;
            Ok(Value::Null)
        }
        "ops.delete" => {
            let dir = Loc { remote: os(&a, "remote"), path: s(&a, "dir") };
            block_on(ops::delete(&dir, &s(&a, "name"), b(&a, "isDir")))?;
            Ok(Value::Null)
        }
        "ops.rename" => {
            let dir = Loc { remote: os(&a, "remote"), path: s(&a, "dir") };
            block_on(ops::rename(&dir, &s(&a, "name"), &s(&a, "newName")))?;
            Ok(Value::Null)
        }
        "ops.paste" => {
            let src = Loc { remote: os(&a, "remote"), path: s(&a, "dir") };
            let dst = Loc { remote: os(&a, "dstRemote"), path: s(&a, "dstDir") };
            block_on(ops::paste(&src, &s(&a, "name"), b(&a, "isDir"), i(&a, "size"), &dst, b(&a, "cut"), events))?;
            Ok(Value::Null)
        }

        // ---- backup, jobs and job logs
        "backup.run" => {
            let job: Job = serde_json::from_value(a)?;
            Ok(json!(block_on(backup::run(job, events))))
        }
        "jobs.all" => to_value(&jobs::all()?),
        "jobs.get" => match jobs::get(&s(&a, "id"))? {
            Some(j) => to_value(&j),
            None => Ok(Value::Null),
        },
        "jobs.save" => {
            jobs::save(serde_json::from_value(a)?)?;
            Ok(Value::Null)
        }
        "jobs.delete" => {
            jobs::delete(&s(&a, "id"))?;
            Ok(Value::Null)
        }
        "joblog.read" => Ok(json!(logs::job_read(&s(&a, "id"), i(&a, "limit").max(0) as usize)?)),
        "joblog.clear" => {
            logs::job_clear(&s(&a, "id"))?;
            Ok(Value::Null)
        }

        // ---- client log
        "log.write" => {
            logs::write(&s(&a, "level"), &s(&a, "tag"), &s(&a, "msg"), &s(&a, "stack"));
            Ok(Value::Null)
        }
        "log.read" => Ok(json!(logs::read())),
        "log.clear" => {
            logs::clear();
            Ok(Value::Null)
        }

        other => Err(Error::io(format!("Unknown core method: {other}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_methods_fail() {
        assert!(dispatch("nope", "{}", None).is_err());
    }

    #[test]
    fn configured_reflects_the_settings() {
        dispatch("configure", r#"{"url":"","token":""}"#, None).unwrap();
        assert_eq!(dispatch("configured", "", None).unwrap(), json!(false));
    }

    #[test]
    fn adapter_round_trip() {
        let info = dispatch("adapter.info", "", None).unwrap();
        assert_eq!(info["name"], json!("rcapi-v1"));
        assert!(dispatch("adapter.set", r#"{"text":"{}"}"#, None).is_err());
    }
}
