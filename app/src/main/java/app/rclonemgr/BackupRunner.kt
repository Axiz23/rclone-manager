package app.rclonemgr

import android.content.Context
import android.text.format.Formatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** rclone-style glob: `*` (no slash), `**`, `?`, `{a,b}`, `[abc]`. Unanchored patterns match at any depth. */
class Glob(pattern: String) {
    private val re: Regex = try {
        val anchored = pattern.startsWith("/")
        val p = pattern.trimStart('/')
        val sb = StringBuilder(if (anchored) "" else "(?:.*/)?")
        var depth = 0
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '*' && i + 1 < p.length && p[i + 1] == '*' -> { sb.append(".*"); i++ }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '{' -> { depth++; sb.append("(?:") }
                c == '}' && depth > 0 -> { depth--; sb.append(")") }
                c == ',' && depth > 0 -> sb.append("|")
                c == '[' && p.indexOf(']', i) > i -> { val e = p.indexOf(']', i); sb.append(p, i, e + 1); i = e }
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        Regex(sb.toString())
    } catch (_: Exception) {
        Regex("(?!)")   // invalid pattern never matches
    }

    fun matches(rel: String) = re.matches(rel)
}

/**
 * Backup through the API (no local rclone): walks the source folder, compares every directory with
 * `GET /files`, uploads what is missing or changed with `PUT /operations/upload`, and in sync mode deletes
 * what no longer exists locally.
 */
object BackupRunner {
    const val TAG = "backup"

    private fun patterns(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    private fun j(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

    fun run(ctx: Context, job: Job, cancel: AtomicBoolean, onLine: (String) -> Unit): Int {
        val ring = ArrayDeque<String>()
        fun log(s: String) = synchronized(ring) {
            if (ring.size >= JobLog.RUN_LINES) ring.removeFirst()
            ring.add(s.take(JobLog.MAX_LINE))
        }
        val uploaded = AtomicInteger(); val failed = AtomicInteger(); val deleted = AtomicInteger()
        val bytes = AtomicLong()
        var code = -1
        val mode = if (job.sync) "sync" else "copy"
        fun progress() = onLine(
            "${uploaded.get()} uploaded · ${failed.get()} failed · ${Formatter.formatShortFileSize(ctx, bytes.get())}"
        )

        val pool = Executors.newFixedThreadPool(job.transfers.coerceIn(1, 8))
        try {
            Api.init(ctx)
            if (!Api.configured()) { log("Server is not configured"); code = -3; return code }
            val root = File(job.source)
            if (!root.isDirectory || root.listFiles() == null) {
                log("Source folder is not readable: ${job.source}"); code = -4; return code
            }
            val ex = patterns(job.exclude).map { Glob(it) }
            val inc = patterns(job.include).map { Glob(it) }
            fun allowed(rel: String) = ex.none { it.matches(rel) } && (inc.isEmpty() || inc.any { it.matches(rel) })

            val futures = ArrayList<Future<*>>()

            fun submit(f: File, remotePath: String, rel: String) {
                futures.add(pool.submit(Runnable {
                    var err: String? = null
                    for (attempt in 0..2) {
                        if (cancel.get()) return@Runnable
                        try {
                            Api.upload(job.remote, remotePath, f, TAG) { bytes.addAndGet(it) }
                            uploaded.incrementAndGet()
                            progress()
                            return@Runnable
                        } catch (e: Exception) {
                            if (cancel.get()) return@Runnable
                            err = e.message ?: e.javaClass.simpleName
                            if (e is ApiException && e.status in 400..499) break
                            try { Thread.sleep(1500L * (attempt + 1)) } catch (_: InterruptedException) { break }
                        }
                    }
                    failed.incrementAndGet()
                    log("FAILED $rel: $err")
                    progress()
                }))
            }

            fun walk(dir: File, rel: String): Unit {
                if (cancel.get()) return
                val children = dir.listFiles()
                if (children == null) { failed.incrementAndGet(); log("Cannot read ${rel.ifEmpty { "/" }}"); return }
                val remoteDir = j(job.remotePath, rel)
                val files = children.filter { it.isFile && allowed(j(rel, it.name)) }

                val rmap: Map<String, Entry>? =
                    if (files.isNotEmpty() || job.sync) {
                        try {
                            Api.list(job.remote, remoteDir).associateBy { it.name }
                        } catch (e: ApiException) {
                            if (e.status == 404) emptyMap() else throw e
                        }
                    } else null

                if (rmap != null) {
                    for (f in files) {
                        val r = rmap[f.name]
                        if (r == null || r.isDir || r.size != f.length() || f.lastModified() > r.modified + 2000)
                            submit(f, j(remoteDir, f.name), j(rel, f.name))
                    }
                    if (job.sync) {
                        val local = children.map { it.name }.toSet()
                        for ((n, e) in rmap) {
                            if (cancel.get()) break
                            if (n in local) continue
                            if (!e.isDir && !allowed(j(rel, n))) continue   // excluded by filters: leave it alone
                            try {
                                Api.delete(job.remote, j(remoteDir, n), e.isDir, TAG)
                                deleted.incrementAndGet()
                                log("Deleted ${j(rel, n)}")
                            } catch (ex2: Exception) {
                                if (!cancel.get()) { failed.incrementAndGet(); log("FAILED delete ${j(rel, n)}: ${ex2.message}") }
                            }
                        }
                    }
                }
                for (d in children) if (d.isDirectory) walk(d, j(rel, d.name))
            }

            walk(root, "")
            for (f in futures) try { f.get() } catch (_: Exception) { }
            code = when {
                cancel.get() -> 130
                failed.get() > 0 -> 1
                else -> 0
            }
            log("Done: ${uploaded.get()} uploaded (${Formatter.formatShortFileSize(ctx, bytes.get())}), " +
                "${failed.get()} failed, ${deleted.get()} deleted")
        } catch (e: Exception) {
            if (!cancel.get()) log("Exception: ${e.message}")
        } finally {
            pool.shutdownNow()
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val block = ArrayList<String>()
                block.add("=== $ts | exit=$code ===")
                block.add("${job.source} -> ${job.dest()} ($mode)")
                synchronized(ring) { block.addAll(ring) }
                JobLog.append(ctx, job.id, block)
            } catch (_: Exception) {
            }
        }
        return code
    }
}
