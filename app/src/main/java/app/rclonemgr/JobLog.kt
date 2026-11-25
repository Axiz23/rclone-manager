package app.rclonemgr

import android.content.Context

/**
 * Per-job backup log. Writing happens inside the core at the end of each run (it keeps the newest 200 lines per
 * job and captures at most 80 lines of 300 characters per run); this facade only reads and clears.
 */
object JobLog {
    /** Lines shown in the UI. */
    const val SHOW_LINES = 80

    fun read(ctx: Context, id: String): List<String> {
        Core.ensureInit(ctx)
        val a = Core.array("joblog.read", Core.args("id" to id, "limit" to SHOW_LINES))
        return List(a.length()) { a.getString(it) }
    }

    fun clear(ctx: Context, id: String) {
        Core.ensureInit(ctx)
        Core.run("joblog.clear", Core.args("id" to id))
    }
}
