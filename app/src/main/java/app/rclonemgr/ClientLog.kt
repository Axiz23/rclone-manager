package app.rclonemgr

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent client log (filesDir/client.log, ~400 KB max) shown in Settings → Logs. */
object ClientLog {
    private const val MAX = 400_000L
    private lateinit var file: File
    private var crashHook = false
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (!::file.isInitialized) file = File(ctx.applicationContext.filesDir, "client.log")
        if (!crashHook) {
            crashHook = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { write("F", "Crash", "Uncaught exception in ${t.name}", e) } catch (_: Throwable) { }
                prev?.uncaughtException(t, e)
            }
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    /** Never write tokens into the log. */
    private fun clean(s: String) = s
        .replace(Regex("(?i)(token|key|authorization)=[^&\\s]+"), "$1=***")
        .replace(Regex("Bearer\\s+\\S+"), "Bearer ***")

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val sb = StringBuilder()
        sb.append(fmt.format(Date())).append(' ').append(level).append(' ').append(tag).append(": ").append(clean(msg)).append('\n')
        if (t != null) {
            Log.getStackTraceString(t).lines().filter { it.isNotBlank() }.take(18).forEach { sb.append("    ").append(clean(it.trim())).append('\n') }
        }
        Log.println(if (level == "I") Log.INFO else Log.ERROR, "RcloneMgr/$tag", clean(msg))
        if (!::file.isInitialized) return
        try {
            file.appendText(sb.toString())
            if (file.length() > MAX) {
                val txt = file.readText()
                file.writeText(txt.substring((txt.length * 0.4).toInt()).substringAfter('\n'))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized fun read(): String = try { if (::file.isInitialized && file.exists()) file.readText() else "" } catch (_: Exception) { "" }
    @Synchronized fun clear() { try { if (::file.isInitialized) file.delete() } catch (_: Exception) { } }
}
