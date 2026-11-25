//! JNI bridge: one native function, `Core.call(method, argsJson, listener)`.
//! Errors become Java exceptions (`ApiException`, `AdapterException`, `IOException`); panics become `RuntimeException`.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use jni::objects::{GlobalRef, JObject, JString, JThrowable, JValue};
use jni::sys::jstring;
use jni::{JNIEnv, JavaVM};

use crate::dispatch::dispatch;
use crate::error::Error;
use crate::model::{Events, Sink};

/// Forwards progress events to the Kotlin `CoreListener`. Calls come from runtime threads, which are attached to
/// the JVM on first use.
struct JniEvents {
    vm: JavaVM,
    target: GlobalRef,
}

impl JniEvents {
    fn call(&self, name: &str, sig: &str, args: &[JValue]) {
        if let Ok(mut env) = self.vm.attach_current_thread_permanently() {
            let _ = env.call_method(self.target.as_obj(), name, sig, args);
            // A listener must never leave an exception pending on a runtime thread.
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }
    }
}

impl Events for JniEvents {
    fn on_bytes(&self, n: i64) {
        self.call("onBytes", "(J)V", &[JValue::Long(n)]);
    }

    fn on_progress(&self, done: i64, total: i64) {
        self.call("onProgress", "(JJ)V", &[JValue::Long(done), JValue::Long(total)]);
    }

    fn on_backup(&self, uploaded: i64, failed: i64, bytes: i64) {
        self.call("onBackup", "(JJJ)V", &[JValue::Long(uploaded), JValue::Long(failed), JValue::Long(bytes)]);
    }
}

fn make_events(env: &mut JNIEnv, listener: &JObject) -> Sink {
    if listener.is_null() {
        return None;
    }
    let vm = env.get_java_vm().ok()?;
    let target = env.new_global_ref(listener).ok()?;
    Some(Arc::new(JniEvents { vm, target }))
}

fn throw(env: &mut JNIEnv, e: Error) {
    let _ = env.exception_clear();
    match e {
        Error::Api { status, message } => {
            if let Ok(msg) = env.new_string(&message) {
                if let Ok(obj) = env.new_object(
                    "app/rclonemgr/ApiException",
                    "(ILjava/lang/String;)V",
                    &[JValue::Int(status), JValue::Object(&msg)],
                ) {
                    let _ = env.throw(JThrowable::from(obj));
                    return;
                }
            }
            let _ = env.exception_clear();
            let _ = env.throw_new("java/io/IOException", message);
        }
        Error::Adapter(message) => {
            if let Ok(msg) = env.new_string(&message) {
                if let Ok(obj) =
                    env.new_object("app/rclonemgr/AdapterException", "(Ljava/lang/String;)V", &[JValue::Object(&msg)])
                {
                    let _ = env.throw(JThrowable::from(obj));
                    return;
                }
            }
            let _ = env.exception_clear();
            let _ = env.throw_new("java/io/IOException", message);
        }
        other => {
            let _ = env.throw_new("java/io/IOException", other.message());
        }
    }
}

fn panic_text(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

/// `external fun call(method: String, args: String, listener: CoreListener?): String` of `object Core`.
/// Returns the JSON text of the result.
#[no_mangle]
pub extern "system" fn Java_app_rclonemgr_Core_call<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    method: JString<'local>,
    args: JString<'local>,
    listener: JObject<'local>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String, Error> {
        let method: String = env.get_string(&method).map_err(|e| Error::io(e.to_string()))?.into();
        let args: String = env.get_string(&args).map_err(|e| Error::io(e.to_string()))?.into();
        let events = make_events(&mut env, &listener);
        let value = dispatch(&method, &args, events)?;
        Ok(value.to_string())
    }));
    let result = match outcome {
        Ok(r) => r,
        Err(p) => Err(Error::io(format!("Internal error: {}", panic_text(p)))),
    };
    match result {
        Ok(text) => match env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => {
            throw(&mut env, e);
            std::ptr::null_mut()
        }
    }
}
