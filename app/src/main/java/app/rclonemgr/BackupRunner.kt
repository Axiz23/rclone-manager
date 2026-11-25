package app.rclonemgr

import android.content.Context
import android.text.format.Formatter

/**
 * Runs a backup job. The engine (folder walk, comparison with the server listing, parallel uploads, sync deletes,
 * include/exclude filters, retries and the job log) is in the Rust core.
 *
 * Exit codes: 0 ok, 1 some files failed, 130 cancelled, -1 failed with an exception,
 * -3 server not configured, -4 source folder not readable.
 */
object BackupRunner {
    /** Cancel tag: `Api.cancel(TAG)` stops the running backup. */
    const val TAG = "backup"

    /** Blocks until the job finishes. [onLine] receives a short progress text (called from a core thread). */
    fun run(ctx: Context, job: Job, onLine: (String) -> Unit): Int {
        Api.init(ctx)
        val listener = object : CoreListener() {
            override fun onBackup(uploaded: Long, failed: Long, bytes: Long) {
                onLine("$uploaded uploaded · $failed failed · ${Formatter.formatShortFileSize(ctx, bytes)}")
            }
        }
        return (Core.run("backup.run", job.toJson(), listener) as? Number)?.toInt() ?: -1
    }
}
