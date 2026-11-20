package app.rclonemgr

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-job log with hard limits on both stored and displayed lines. */
object JobLog {
    const val STORE_LINES = 200   // max lines kept on disk per job
    const val SHOW_LINES = 80     // max lines shown in the UI
    const val RUN_LINES = 80      // max non-progress lines captured per run
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

object RcloneRunner {
    private val prefix = Regex("^\\S+ \\S+ [A-Z]+:\\s*")
    private val statRe = Regex("^.*/s, ETA\\s.*$")   // rclone --stats-one-line output

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "librclone.so")
    fun confFile(ctx: Context) = File(ctx.filesDir, "rclone.conf")

    fun remotes(ctx: Context): List<String> = remoteInfo(ctx).map { it.first }

    /** (name, type) for every [section] of rclone.conf. */
    fun remoteInfo(ctx: Context): List<Pair<String, String>> {
        val f = confFile(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        var name: String? = null
        var type = ""
        for (line in f.readLines()) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                name?.let { out.add(it to type) }
                name = t.substring(1, t.length - 1)
                type = ""
            } else if (t.startsWith("type")) {
                val kv = t.split("=", limit = 2)
                if (kv.size == 2 && kv[0].trim() == "type") type = kv[1].trim()
            }
        }
        name?.let { out.add(it to type) }
        return out
    }

    private fun patterns(s: String) =
        s.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Runs rclone as a child process. Returns the exit code (0 = success). */
    fun run(ctx: Context, job: Job, setProc: (Process?) -> Unit, onLine: (String) -> Unit): Int {
        val ring = ArrayDeque<String>()
        var lastStat: String? = null
        var code = -1
        val mode = if (job.sync) "sync" else "copy"
        try {
            val bin = binary(ctx)
            if (!bin.exists()) { ring.add("rclone binary not found: $bin"); code = -2; return code }
            if (!confFile(ctx).exists()) { ring.add("rclone.conf has not been imported"); code = -3; return code }

            val cmd = mutableListOf(
                bin.path, mode, job.source, job.dest(),
                "--config", confFile(ctx).path,
                "--cache-dir", ctx.cacheDir.path,
                "--transfers", job.transfers.toString(), "--checkers", maxOf(2, job.transfers).toString(),
                "--buffer-size", if (job.transfers > 4) "2M" else "4M", "--use-mmap", "--max-backlog", "500",
                "--retries", "2", "--low-level-retries", "5",
                "--stats", "3s", "--stats-one-line", "--stats-log-level", "NOTICE"
            )

            // Filters: excludes first (first match wins), then includes, then "exclude everything else"
            val ex = patterns(job.exclude)
            val inc = patterns(job.include)
            if (ex.isNotEmpty() || inc.isNotEmpty()) {
                val rules = ex.map { "- $it" } + inc.map { "+ $it" } + (if (inc.isNotEmpty()) listOf("- **") else emptyList())
                val ff = File(ctx.cacheDir, "filter-${job.id}.txt")
                ff.writeText(rules.joinToString("\n") + "\n")
                cmd += listOf("--filter-from", ff.path)
            }

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.directory(ctx.filesDir)
            pb.environment().apply {
                put("GOMEMLIMIT", "96MiB")
                put("GOGC", "50")
                put("HOME", ctx.filesDir.path)
                put("TMPDIR", ctx.cacheDir.path)
            }
            val p = pb.start()
            setProc(p)
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.replace(prefix, "").trim()
                if (line.isEmpty()) return@forEachLine
                if (statRe.matches(line)) {
                    lastStat = line            // keep only the latest progress line
                } else {
                    if (ring.size >= JobLog.RUN_LINES) ring.removeFirst()
                    ring.add(raw.trim().take(JobLog.MAX_LINE))
                }
                onLine(line)
            }
            code = p.waitFor()
        } catch (e: Exception) {
            ring.add("Exception: ${e.message}".take(JobLog.MAX_LINE))
        } finally {
            setProc(null)
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val block = ArrayList<String>()
                block.add("=== $ts | exit=$code ===")
                block.add("${job.source} -> ${job.dest()} ($mode)")
                block.addAll(ring)
                lastStat?.let { block.add("last progress: ${it.take(JobLog.MAX_LINE)}") }
                JobLog.append(ctx, job.id, block)
            } catch (_: Exception) {
            }
        }
        return code
    }
}
