package app.rclonemgr

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom

class Entry(val name: String, val size: Long, val isDir: Boolean) {
    val ext: String = name.substringAfterLast('.', "").lowercase()
    val kind: Int = when {
        isDir -> K_DIR
        ext in IMG -> K_IMAGE
        ext in VID -> K_VIDEO
        else -> K_FILE
    }
    var thumbName: String? = null   // matching file inside the .thumbnails folder

    companion object {
        const val K_DIR = 0
        const val K_IMAGE = 1
        const val K_VIDEO = 2
        const val K_FILE = 3
        private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VID = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi")
    }
}

/** Names of the images in the folder being browsed (small, avoids huge Intents). */
object MediaHolder {
    @Volatile var names: List<String> = emptyList()
}

/**
 * One local `rclone rcd` process (127.0.0.1, random port, random password) used for listing and for
 * streaming files. It only runs while a browse/viewer screen is in use.
 */
object RcServer {
    private var proc: Process? = null
    private var refs = 0
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var port = 0
    @Volatile var auth = ""
    @Volatile private var ready = false
    @Volatile var pins = 0   // > 0 while a player needs the server to stay alive

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
        val raw = try { c.errorStream?.bufferedReader()?.readText()?.take(2000) ?: "" } catch (_: Exception) { "" }
        val msg = try { JSONObject(raw).optString("error", raw) } catch (_: Exception) { raw }
        return "HTTP ${c.responseCode}: ${msg.ifBlank { "error" }}".take(240)
    }

    /** Lists one directory. `dir` is relative to the remote root ("" = root). */
    fun list(remote: String, dir: String): List<Entry> {
        if (!RcServer.awaitReady()) throw IOException("rclone server did not start")
        val c = open("http://127.0.0.1:${RcServer.port}/operations/list", "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("fs", "$remote:").put("remote", dir)
                .put("opt", JSONObject().put("noModTime", true).put("noMimeType", true)).toString()
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
        var name = ""; var size = 0L; var dir = false
        r.beginObject()
        while (r.hasNext()) {
            val n = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue }
            when (n) {
                "Name" -> name = r.nextString()
                "Size" -> size = r.nextLong()
                "IsDir" -> dir = r.nextBoolean()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Entry(name, size, dir)
    }

    /** Opens a file for reading (supports seeking via Range on the server side). */
    fun get(url: String): HttpURLConnection {
        if (!RcServer.awaitReady()) throw IOException("rclone server did not start")
        val c = open(url)
        if (c.responseCode !in 200..299) { val m = errorText(c); c.disconnect(); throw IOException(m) }
        return c
    }
}
