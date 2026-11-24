package app.rclonemgr

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** File operations. Device-only work uses java.io; anything touching a remote goes through the RCAPI server. */
object Ops {
    const val TAG = "op"

    class Cancelled : Exception("Cancelled")

    /** Aggregates byte counts and reports at most ~5 times per second. */
    private class Prog(private val total: Long, private val cb: (Long, Long) -> Unit) {
        private var done = 0L
        private var last = 0L
        fun add(n: Long) {
            done += n
            val now = System.currentTimeMillis()
            if (now - last > 200) { last = now; cb(done, total) }
        }
    }

    private fun check(c: AtomicBoolean) { if (c.get()) throw Cancelled() }

    private fun guard(c: AtomicBoolean, block: () -> Unit) {
        try {
            block()
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            if (c.get() || Api.isCanceled(e)) throw Cancelled()
            throw e
        }
    }

    fun mkdir(dir: Loc, name: String) {
        if (dir.remote == null) {
            val f = File(dir.path, name)
            if (f.exists()) throw IOException("\"$name\" already exists")
            if (!f.mkdirs()) throw IOException("Cannot create folder")
        } else {
            Api.mkdir(dir.remote, dir.child(name).path)
        }
    }

    fun delete(dir: Loc, e: Entry, cancel: AtomicBoolean, @Suppress("UNUSED_PARAMETER") progress: (Long, Long) -> Unit) {
        check(cancel)
        if (dir.remote == null) {
            val f = File(dir.path, e.name)
            val ok = if (e.isDir) f.deleteRecursively() else f.delete()
            if (!ok) throw IOException("Cannot delete ${e.name}")
        } else {
            guard(cancel) { Api.delete(dir.remote, dir.child(e.name).path, e.isDir, TAG) }
        }
    }

    fun rename(dir: Loc, e: Entry, newName: String, cancel: AtomicBoolean, @Suppress("UNUSED_PARAMETER") progress: (Long, Long) -> Unit) {
        check(cancel)
        if (dir.remote == null) {
            val t = File(dir.path, newName)
            if (t.exists()) throw IOException("\"$newName\" already exists")
            if (!File(dir.path, e.name).renameTo(t)) throw IOException("Cannot rename")
        } else {
            guard(cancel) { Api.rename(dir.remote, dir.child(e.name).path, newName, TAG) }
        }
    }

    private fun copyName(n: String): String {
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) + " (copy)" + n.substring(dot) else "$n (copy)"
    }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Copies or moves one entry from directory `src` to directory `dst` (existing files are overwritten). */
    fun paste(src: Loc, e: Entry, dst: Loc, cut: Boolean, cancel: AtomicBoolean, progress: (Long, Long) -> Unit) {
        if (src == dst && cut) return
        val dstName = if (src == dst) copyName(e.name) else e.name
        if (e.isDir) {
            val s = src.child(e.name)
            if (s.remote == dst.remote && (dst.path == s.path || dst.path.startsWith(s.path + "/")))
                throw IOException("Cannot copy a folder into itself")
        }
        check(cancel)
        val sPath = src.child(e.name).path
        val dPath = dst.child(dstName).path

        when {
            // device -> device
            src.remote == null && dst.remote == null -> {
                val s = File(sPath)
                val d = File(dPath)
                if (cut) {
                    if (!s.renameTo(d)) { s.copyRecursively(d, true); s.deleteRecursively() }
                } else {
                    s.copyRecursively(d, true)
                }
            }
            // server -> server (done on the server, no data passes through the phone)
            src.remote != null && dst.remote != null -> guard(cancel) {
                if (cut) Api.move(src.remote, sPath, dst.remote, dPath, TAG)
                else Api.copy(src.remote, sPath, dst.remote, dPath, TAG)
            }
            // device -> server
            src.remote == null && dst.remote != null -> {
                val root = File(sPath)
                val p = Prog(sizeOf(root), progress)
                guard(cancel) { upload(root, dst.remote, dPath, cancel, p) }
                if (cut) { if (!(if (e.isDir) root.deleteRecursively() else root.delete())) throw IOException("Copied, but cannot delete the original") }
            }
            // server -> device
            else -> {
                val remote = src.remote!!
                val total = if (e.isDir) remoteSize(remote, sPath, cancel) else e.size
                val p = Prog(total, progress)
                guard(cancel) { download(remote, sPath, e.isDir, File(dPath), cancel, p) }
                if (cut) guard(cancel) { Api.delete(remote, sPath, e.isDir, TAG) }
            }
        }
    }

    private fun upload(f: File, remote: String, path: String, cancel: AtomicBoolean, p: Prog) {
        check(cancel)
        if (f.isDirectory) {
            Api.mkdir(remote, path, TAG)
            for (c in f.listFiles() ?: throw IOException("Cannot read ${f.name}"))
                upload(c, remote, "$path/${c.name}", cancel, p)
        } else {
            Api.upload(remote, path, f, TAG) { p.add(it) }
        }
    }

    private fun remoteSize(remote: String, path: String, cancel: AtomicBoolean): Long {
        var t = 0L
        for (c in Api.list(remote, path)) {
            check(cancel)
            t += if (c.isDir) remoteSize(remote, "$path/${c.name}", cancel) else c.size
        }
        return t
    }

    private fun download(remote: String, path: String, isDir: Boolean, dest: File, cancel: AtomicBoolean, p: Prog) {
        check(cancel)
        if (isDir) {
            if (!dest.isDirectory && !dest.mkdirs()) throw IOException("Cannot create ${dest.name}")
            for (c in Api.list(remote, path)) download(remote, "$path/${c.name}", c.isDir, File(dest, c.name), cancel, p)
        } else {
            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                Api.download(remote, path, tmp, TAG) { p.add(it) }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Cannot write ${dest.name}")
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}
