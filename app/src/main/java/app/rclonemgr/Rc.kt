package app.rclonemgr

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.time.OffsetDateTime

/**
 * One local `rclone rcd` process (127.0.0.1, random port, random password) used for listing, file operations
 * and streaming. It only runs while a screen needs it.
 */
object RcServer {
    private var proc: Process? = null
    private var refs = 0
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var port = 0
    @Volatile var auth = ""
    @Volatile private var ready = false
    @Volatile var pins = 0   // > 0 while a player / long operation needs the server to stay alive

    @Synchronized fun acquire(ctx: Context) { refs++; ensureRunning(ctx) }

    @Synchronized fun release() {
        if (--refs <= 0) { refs = 0; kill() }
    }

    @Synchronized fun ensureRunning(ctx: Context) {
        if (refs > 0 && proc?.isAlive != true) start(ctx)
    }

    fun stopLater() {
        handler.postDelayed({ if (!Ui.visible && pins == 0) kill() }, 60_000)
    }

    @Synchronized private fun kill() {
        proc?.destroy(); proc = null; ready = false
    }

    private fun start(ctx: Context) {
        ready = false
        val s = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        port = s.localPort
        s.close()
        val user = "rcm"
        val pass = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        auth = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        val pb = ProcessBuilder(
            listOf(
                RcloneRunner.binary(ctx).path, "rcd",
                "--config", RcloneRunner.confFile(ctx).path,
                "--cache-dir", ctx.cacheDir.path,
                "--rc-addr", "127.0.0.1:$port",
                "--rc-user", user, "--rc-pass", pass,
                "--rc-serve",
                "--buffer-size", "2M", "--use-mmap"
            )
        ).redirectErrorStream(true)
        pb.directory(ctx.filesDir)
        pb.environment().apply {
            put("GOMEMLIMIT", "96MiB"); put("GOGC", "50")
            put("HOME", ctx.filesDir.path); put("TMPDIR", ctx.cacheDir.path)
        }
        val p = pb.start()
        proc = p
        Thread { try { p.inputStream.bufferedReader().forEachLine { } } catch (_: Exception) { } }
            .apply { isDaemon = true; start() }
    }

    /** Blocks (up to ~5 s) until the server accepts connections. Call from a worker thread. */
    fun awaitReady(): Boolean {
        if (ready) return true
        repeat(50) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                ready = true
                return true
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }
}

object Rc {
    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", RcServer.auth)
        c.connectTimeout = 5000
        c.readTimeout = 60000
        return c
    }

    fun fileUrl(remote: String, dir: String, name: String): String =
        "http://127.0.0.1:${RcServer.port}/" + Uri.encode("[$remote:$dir]", "/:") + "/" + Uri.encode(name)

    private fun errorText(c: HttpURLConnection): String {
        val raw = try { c.errorStream?.bufferedReader()?.use { it.readText() }?.take(2000) ?: "" } catch (_: Exception) { "" }
        val msg = try { JSONObject(raw).optString("error", raw) } catch (_: Exception) { raw }
        return "HTTP ${c.responseCode}: ${msg.ifBlank { "error" }}".take(240)
    }

    fun parseTime(s: String): Long =
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }

    /** Generic rc call (small JSON in / out). */
    fun post(path: String, p: JSONObject, readTimeoutMs: Int = 30000): JSONObject {
        if (!RcServer.awaitReady()) throw IOException("rclone server did not start")
        val c = open("http://127.0.0.1:${RcServer.port}/$path", "POST")
        try {
            c.readTimeout = readTimeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(p.toString().toByteArray()) }
            if (c.responseCode != 200) throw IOException(errorText(c))
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            return if (txt.isBlank()) JSONObject() else JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }

    /** Lists one directory. `dir` is relative to the remote root ("" = root). */
    fun list(remote: String, dir: String): List<Entry> {
        if (!RcServer.awaitReady()) throw IOException("rclone server did not start")
        val c = open("http://127.0.0.1:${RcServer.port}/operations/list", "POST")
        try {
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("fs", "$remote:").put("remote", dir)
                .put("opt", JSONObject().put("noMimeType", true)).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw IOException(errorText(c))
            val out = ArrayList<Entry>()
            JsonReader(InputStreamReader(c.inputStream, "UTF-8")).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "list") {
                        r.beginArray()
                        while (r.hasNext()) out.add(readEntry(r))
                        r.endArray()
                    } else r.skipValue()
                }
                r.endObject()
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun readEntry(r: JsonReader): Entry {
        var name = ""; var size = 0L; var dir = false; var mod = 0L
        r.beginObject()
        while (r.hasNext()) {
            val n = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue }
            when (n) {
                "Name" -> name = r.nextString()
                "Size" -> size = r.nextLong()
                "IsDir" -> dir = r.nextBoolean()
                "ModTime" -> mod = parseTime(r.nextString())
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Entry(name, if (dir) 0L else maxOf(size, 0L), dir, mod)
    }

    /** Opens a file for reading (the server supports Range requests, so seeking works). */
    fun get(url: String): HttpURLConnection {
        if (!RcServer.awaitReady()) throw IOException("rclone server did not start")
        val c = open(url)
        if (c.responseCode !in 200..299) throw IOException(errorText(c))
        return c
    }

    /** Reads the first `n` bytes of a file (Range request); never downloads the whole file. */
    fun readHead(url: String, n: Int): ByteArray {
        if (!RcServer.awaitReady()) throw IOException("rclone server did not start")
        val c = open(url)
        c.setRequestProperty("Range", "bytes=0-${n - 1}")
        try {
            if (c.responseCode !in 200..299) throw IOException(errorText(c))
            val buf = ByteArray(n)
            var got = 0
            c.inputStream.use { i ->
                while (got < n) {
                    val r = i.read(buf, got, n - got)
                    if (r <= 0) break
                    got += r
                }
            }
            return buf.copyOf(got)
        } finally {
            c.disconnect()
        }
    }

    /** Reads a small file fully (connection is left alive for reuse). Returns null if larger than `max`. */
    fun readBytes(url: String, max: Long): ByteArray? {
        val c = get(url)
        if (c.contentLengthLong > max) { c.disconnect(); return null }
        val out = ByteArrayOutputStream()
        c.inputStream.use { i ->
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = i.read(buf)
                if (n <= 0) break
                total += n
                if (total > max) { c.disconnect(); return null }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
