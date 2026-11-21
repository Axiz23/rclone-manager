package app.rclonemgr

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** File operations. Local-only operations use java.io; anything touching a remote goes through the rclone server. */
object Ops {
    class Cancelled : Exception("Cancelled")

    private fun rcJob(path: String, p: JSONObject, cancel: AtomicBoolean, progress: (Long, Long) -> Unit) {
        p.put("_async", true)
        val id = Rc.post(path, p).getLong("jobid")
        while (true) {
            Thread.sleep(700)
            if (cancel.get()) {
                try { Rc.post("job/stop", JSONObject().put("jobid", id)) } catch (_: Exception) { }
                throw Cancelled()
            }
            val st = Rc.post("job/status", JSONObject().put("jobid", id))
            if (st.optBoolean("finished")) {
                if (!st.optBoolean("success")) throw IOException(st.optString("error").ifBlank { "Operation failed" })
                return
            }
            try {
                val s = Rc.post("core/stats", JSONObject())
                progress(s.optLong("bytes"), s.optLong("totalBytes"))
            } catch (_: Exception) { }
        }
    }

    fun mkdir(dir: Loc, name: String) {
        if (dir.remote == null) {
            val f = File(dir.path, name)
            if (f.exists()) throw IOException("\"$name\" already exists")
            if (!f.mkdirs()) throw IOException("Cannot create folder")
        } else {
            Rc.post("operations/mkdir", JSONObject().put("fs", dir.fs()).put("remote", name))
        }
    }

    fun createFile(dir: Loc, name: String) {
        if (dir.remote != null) throw IOException("Creating files on remotes is not supported")
        val f = File(dir.path, name)
        if (f.exists()) throw IOException("\"$name\" already exists")
        if (!f.createNewFile()) throw IOException("Cannot create file")
    }

    fun delete(dir: Loc, e: Entry, cancel: AtomicBoolean, progress: (Long, Long) -> Unit) {
        if (dir.remote == null) {
            val f = File(dir.path, e.name)
            val ok = if (e.isDir) f.deleteRecursively() else f.delete()
            if (!ok) throw IOException("Cannot delete ${e.name}")
        } else {
            rcJob(
                if (e.isDir) "operations/purge" else "operations/deletefile",
                JSONObject().put("fs", dir.fs()).put("remote", e.name), cancel, progress
            )
        }
    }

    fun rename(dir: Loc, e: Entry, newName: String, cancel: AtomicBoolean, progress: (Long, Long) -> Unit) {
        if (dir.remote == null) {
            val t = File(dir.path, newName)
            if (t.exists()) throw IOException("\"$newName\" already exists")
            if (!File(dir.path, e.name).renameTo(t)) throw IOException("Cannot rename")
        } else if (e.isDir) {
            rcJob(
                "sync/move",
                JSONObject().put("srcFs", dir.child(e.name).fs()).put("dstFs", dir.child(newName).fs())
                    .put("deleteEmptySrcDirs", true), cancel, progress
            )
        } else {
            rcJob(
                "operations/movefile",
                JSONObject().put("srcFs", dir.fs()).put("srcRemote", e.name)
                    .put("dstFs", dir.fs()).put("dstRemote", newName), cancel, progress
            )
        }
    }

    private fun copyName(n: String): String {
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) + " (copy)" + n.substring(dot) else "$n (copy)"
    }

    /** Copies or moves one entry from directory `src` to directory `dst` (existing files are overwritten). */
    fun paste(src: Loc, e: Entry, dst: Loc, cut: Boolean, cancel: AtomicBoolean, progress: (Long, Long) -> Unit) {
        if (src == dst && cut) return
        val dstName = if (src == dst) copyName(e.name) else e.name
        if (e.isDir) {
            val s = src.child(e.name)
            if (s.remote == dst.remote && (dst.path == s.path || dst.path.startsWith(s.path + "/")))
                throw IOException("Cannot copy a folder into itself")
        }
        if (src.remote == null && dst.remote == null) {
            val s = File(src.path, e.name)
            val d = File(dst.path, dstName)
            if (cut) {
                if (!s.renameTo(d)) { s.copyRecursively(d, true); s.deleteRecursively() }
            } else {
                s.copyRecursively(d, true)
            }
            return
        }
        if (e.isDir) {
            val p = JSONObject().put("srcFs", src.child(e.name).fs()).put("dstFs", dst.child(dstName).fs())
            if (cut) p.put("deleteEmptySrcDirs", true)
            rcJob(if (cut) "sync/move" else "sync/copy", p, cancel, progress)
        } else {
            val p = JSONObject().put("srcFs", src.fs()).put("srcRemote", e.name)
                .put("dstFs", dst.fs()).put("dstRemote", dstName)
            rcJob(if (cut) "operations/movefile" else "operations/copyfile", p, cancel, progress)
        }
    }
}
