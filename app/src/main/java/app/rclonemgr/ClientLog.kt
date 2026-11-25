package app.rclonemgr

import android.content.Context
import android.util.Log

/**
 * Persistent client log shown in Settings → Logs. The file, size limit, token scrubbing and logcat output live in
 * the Rust core; this facade adds stack traces and the uncaught-exception hook.
 */
object ClientLog {
    private var crashHook = false

    fun init(ctx: Context) {
        Core.ensureInit(ctx)
        if (crashHook) return
        crashHook = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write("F", "Crash", "Uncaught exception in ${t.name}", e) } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val stack = if (t == null) "" else Log.getStackTraceString(t).lines().filter { it.isNotBlank() }.take(18).joinToString("\n")
        try {
            Core.run("log.write", Core.args("level" to level, "tag" to tag, "msg" to msg, "stack" to stack))
        } catch (_: Throwable) {
            Log.println(Log.ERROR, "RcloneMgr/$tag", msg)   // the core is unavailable: at least reach logcat
        }
    }

    fun read(): String = try { Core.string("log.read") ?: "" } catch (_: Throwable) { "" }

    fun clear() { try { Core.run("log.clear") } catch (_: Throwable) { } }
}
