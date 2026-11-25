package app.rclonemgr

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File operations, implemented by the Rust core: device-only work uses plain file I/O, anything touching a remote
 * goes through the server API (server-to-server copies run on the server, no data passes through the phone).
 */
object Ops {
    /** Cancel tag: `Api.cancel(TAG)` aborts the running operation. */
    const val TAG = "op"

    class Cancelled : Exception("Cancelled")

    private fun check(c: AtomicBoolean) { if (c.get()) throw Cancelled() }

    private fun at(dir: Loc, vararg more: Pair<String, Any?>): JSONObject =
        Core.args("remote" to dir.remote, "dir" to dir.path, *more)

    fun mkdir(dir: Loc, name: String) {
        Core.run("ops.mkdir", at(dir, "name" to name))
    }

    fun delete(dir: Loc, e: Entry, cancel: AtomicBoolean, @Suppress("UNUSED_PARAMETER") progress: (Long, Long) -> Unit) {
        check(cancel)
        Core.run("ops.delete", at(dir, "name" to e.name, "isDir" to e.isDir))
    }

    fun rename(dir: Loc, e: Entry, newName: String, cancel: AtomicBoolean, @Suppress("UNUSED_PARAMETER") progress: (Long, Long) -> Unit) {
        check(cancel)
        Core.run("ops.rename", at(dir, "name" to e.name, "newName" to newName))
    }

    /** Copies or moves one entry from folder [src] to folder [dst] (existing files are overwritten). */
    fun paste(src: Loc, e: Entry, dst: Loc, cut: Boolean, cancel: AtomicBoolean, progress: (Long, Long) -> Unit) {
        check(cancel)
        val listener = object : CoreListener() {
            override fun onProgress(done: Long, total: Long) = progress(done, total)
        }
        Core.run(
            "ops.paste",
            at(src, "name" to e.name, "isDir" to e.isDir, "size" to e.size, "dstRemote" to dst.remote, "dstDir" to dst.path, "cut" to cut),
            listener
        )
    }
}
