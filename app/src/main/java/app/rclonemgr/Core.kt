package app.rclonemgr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.TimeZone

/** The server answered with an error status. Thrown by the native core (the constructor is called through JNI). */
class ApiException(val status: Int, message: String) : IOException(message)

/** The connection adapter profile is invalid. Thrown by the native core (called through JNI). */
class AdapterException(message: String) : Exception(message)

/**
 * Progress callbacks from the native core. They arrive on core threads, so implementations must be quick and must
 * not call back into [Core]; post to the UI thread instead. The methods are looked up by name from Rust.
 */
open class CoreListener {
    /** [n] more bytes were transferred. */
    open fun onBytes(n: Long) {}

    /** File operation progress: [done] of [total] bytes (total is 0 when unknown). */
    open fun onProgress(done: Long, total: Long) {}

    /** Backup counters. */
    open fun onBackup(uploaded: Long, failed: Long, bytes: Long) {}
}

/**
 * Bridge to the Rust core (`core/`, built as `librcm_core.so`). The core owns the API client, backup engine, file
 * operations, caches, job storage and logging; Kotlin keeps the UI and Android-specific work.
 *
 * Every call blocks: use it from a worker thread. Errors surface as [ApiException], [AdapterException] or
 * [IOException] (message "Canceled" when aborted by [Api.cancel]).
 */
object Core {
    init {
        System.loadLibrary("rcm_core")
    }

    /** Implemented in Rust (`bridge.rs`): [method] + JSON [args] in, JSON text out. */
    private external fun call(method: String, args: String, listener: CoreListener?): String

    @Volatile private var ready = false

    /** Directories managed by the core (valid after [ensureInit]). */
    class Dirs(val lists: String, val files: String, val thumbs: String)

    @Volatile private var dirsRef: Dirs? = null

    val dirs: Dirs
        get() = dirsRef ?: synchronized(this) {
            dirsRef ?: (run("dirs") as JSONObject).let {
                Dirs(it.getString("lists"), it.getString("files"), it.getString("thumbs"))
            }.also { dirsRef = it }
        }

    /** Gives the core its directories; safe to call repeatedly. */
    fun ensureInit(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = ctx.applicationContext
            run("init", args("files" to app.filesDir.path, "cache" to app.cacheDir.path, "tz" to tzSeconds()))
            ready = true
        }
    }

    /** Current UTC offset in seconds (the core cannot ask Android for it). */
    fun tzSeconds(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

    fun args(vararg p: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in p) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    /** Calls a core method. Returns the parsed result: JSONObject, JSONArray, String, Number, Boolean or null. */
    fun run(method: String, args: JSONObject? = null, listener: CoreListener? = null): Any? {
        val text = call(method, args?.toString() ?: "", listener)
        val v = JSONTokener(text).nextValue()
        return if (v == JSONObject.NULL) null else v
    }

    fun string(method: String, args: JSONObject? = null): String? = run(method, args) as? String

    fun long(method: String, args: JSONObject? = null): Long = (run(method, args) as? Number)?.toLong() ?: 0L

    fun bool(method: String, args: JSONObject? = null): Boolean = run(method, args) as? Boolean ?: false

    fun array(method: String, args: JSONObject? = null): JSONArray = run(method, args) as? JSONArray ?: JSONArray()
}
