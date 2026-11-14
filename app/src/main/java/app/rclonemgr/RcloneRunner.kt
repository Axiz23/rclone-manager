package app.rclonemgr

import android.content.Context
import java.io.File
import java.util.Date

object RcloneRunner {
    private val prefix = Regex("^\\S+ \\S+ [A-Z]+:\\s*")

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "librclone.so")
    fun confFile(ctx: Context) = File(ctx.filesDir, "rclone.conf")
    fun logFile(ctx: Context, id: String) = File(File(ctx.filesDir, "logs"), "$id.log")

    fun remotes(ctx: Context): List<String> {
        val f = confFile(ctx)
        if (!f.exists()) return emptyList()
        return Regex("^\\[(.+)]\\s*$", RegexOption.MULTILINE).findAll(f.readText()).map { it.groupValues[1] }.toList()
    }

    /** Menjalankan rclone sebagai proses anak. Mengembalikan exit code (0 = sukses). */
    fun run(ctx: Context, job: Job, setProc: (Process?) -> Unit, onLine: (String) -> Unit): Int {
        val ring = ArrayDeque<String>()
        var code = -1
        try {
            val bin = binary(ctx)
            if (!bin.exists()) { ring.add("Binary rclone tidak ada: $bin"); return -2 }
            if (!confFile(ctx).exists()) { ring.add("rclone.conf belum diimpor"); return -3 }

            val cmd = listOf(
                bin.path, if (job.sync) "sync" else "copy", job.source, job.dest(),
                "--config", confFile(ctx).path,
                "--cache-dir", ctx.cacheDir.path,
                "--transfers", "1", "--checkers", "2",
                "--buffer-size", "4M", "--use-mmap", "--max-backlog", "500",
                "--retries", "2", "--low-level-retries", "5",
                "--stats", "3s", "--stats-one-line", "--stats-log-level", "NOTICE"
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.directory(ctx.filesDir)
            pb.environment().apply {
                put("GOMEMLIMIT", "96MiB")   // batasi heap Go -> rclone tetap di bawah ~100MB
                put("GOGC", "50")
                put("HOME", ctx.filesDir.path)
                put("TMPDIR", ctx.cacheDir.path)
            }
            val p = pb.start()
            setProc(p)
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.replace(prefix, "").trim()
                if (line.isEmpty()) return@forEachLine
                if (ring.size >= 200) ring.removeFirst()
                ring.add(raw)
                onLine(line)
            }
            code = p.waitFor()
        } catch (e: Exception) {
            ring.add("Exception: ${e.message}")
        } finally {
            setProc(null)
            try {
                val lf = logFile(ctx, job.id)
                lf.parentFile?.mkdirs()
                lf.writeText("== ${Date()} · exit=$code\n" + ring.joinToString("\n"))
            } catch (_: Exception) {
            }
        }
        return code
    }
}
