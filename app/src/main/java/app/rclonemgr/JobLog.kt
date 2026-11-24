package app.rclonemgr

import android.content.Context
import java.io.File

/** Per-job log with hard limits on both stored and displayed lines. */
object JobLog {
    const val STORE_LINES = 200   // max lines kept on disk per job
    const val SHOW_LINES = 80     // max lines shown in the UI
    const val RUN_LINES = 80      // max lines captured per run
    const val MAX_LINE = 300      // max characters per line

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "logs"), "$id.log")

    @Synchronized
    fun append(ctx: Context, id: String, lines: List<String>) {
        val f = file(ctx, id)
        f.parentFile?.mkdirs()
        val old = if (f.exists()) f.readLines() else emptyList()
        f.writeText((old + lines).takeLast(STORE_LINES).joinToString("\n"))
    }

    @Synchronized
    fun read(ctx: Context, id: String): List<String> {
        val f = file(ctx, id)
        return if (f.exists()) f.readLines().takeLast(SHOW_LINES) else emptyList()
    }

    @Synchronized
    fun clear(ctx: Context, id: String) { file(ctx, id).delete() }
}
