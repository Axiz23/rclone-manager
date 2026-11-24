package app.rclonemgr

import android.content.Context
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** Client for the RCAPI server (/api/v1). Every method blocks: call it from a worker thread. */
object Api {
    @Volatile private var base = ""
    @Volatile private var token = ""
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        base = Prefs.serverUrl(app)
        token = Prefs.token(app)
    }

    fun configured() = base.isNotEmpty() && token.isNotEmpty()
    fun maxUpload(): Long = if (::app.isInitialized) Prefs.maxUpload(app) else 0L

    /** One shared client: HTTP/2 multiplexing over TLS makes thumbnail grids cheap. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            })
            .build()
    }
    private val thumbClient: OkHttpClient by lazy { client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }
    private val uploadClient: OkHttpClient by lazy { client.newBuilder().readTimeout(15, TimeUnit.MINUTES).build() }

    // ------------------------------------------------------------------ plumbing

    private fun url(path: String, vararg q: Pair<String, String>): HttpUrl {
        val b = base.toHttpUrlOrNull() ?: throw IOException("Invalid server URL")
        val nb = b.newBuilder().addPathSegments("api/v1/$path")
        for ((k, v) in q) nb.addQueryParameter(k, v)
        return nb.build()
    }

    private fun get(path: String, vararg q: Pair<String, String>) = Request.Builder().url(url(path, *q)).build()

    private fun fail(r: Response): ApiException {
        val raw = try { r.body?.string()?.take(2000) ?: "" } catch (_: Exception) { "" }
        val msg = try { JSONObject(raw).optString("error", raw) } catch (_: Exception) { raw }
        val text = when (r.code) {
            401 -> "Unauthorized (check the token)"
            else -> msg.ifBlank { "HTTP ${r.code}" }
        }
        return ApiException(r.code, text.take(240))
    }

    private fun <T> exec(req: Request, c: OkHttpClient = client, block: (Response) -> T): T {
        if (!configured()) throw IOException("Server is not configured")
        c.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw fail(r)
            return block(r)
        }
    }

    fun cancel(tag: Any) {
        val d = client.dispatcher
        for (c in d.queuedCalls() + d.runningCalls()) if (c.request().tag() == tag) c.cancel()
    }

    fun isCanceled(e: Exception) = e is IOException && e.message == "Canceled"

    fun parseTime(s: String): Long =
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }

    /** Same id the server uses: base64url("remote:path"), no padding. */
    fun encodeId(remote: String, path: String): String =
        Base64.encodeToString("$remote:$path".toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    // ------------------------------------------------------------------ setup

    /** Saves URL + token only if the server answers /server/settings with them. */
    fun tryConfigure(ctx: Context, url: String, tok: String): JSONObject {
        val ob = base; val ot = token
        base = url.trim().trimEnd('/'); token = tok.trim()
        try {
            val s = settings()
            Prefs.setServer(ctx, base, token)
            Prefs.setMaxUpload(ctx, s.optLong("maxUploadBytes", 0L))
            Prefs.setRemotes(ctx, names(s))
            return s
        } catch (e: Exception) {
            base = ob; token = ot
            throw e
        }
    }

    private fun names(s: JSONObject): List<String> {
        val a = s.optJSONArray("remotes") ?: return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    fun health(): JSONObject = exec(get("health")) { JSONObject(it.body!!.string()) }

    /** GET /server/settings: remotes, thumbnail settings, upload limit. Also refreshes the cached values. */
    fun settings(): JSONObject = exec(get("server/settings")) { JSONObject(it.body!!.string()) }

    fun remotes(): List<String> = exec(get("remotes")) { r ->
        val a = JSONObject(r.body!!.string()).getJSONArray("remotes")
        List(a.length()) { a.getString(it) }
    }

    // ------------------------------------------------------------------ browse

    fun list(remote: String, path: String): List<Entry> =
        exec(get("files", "remote" to remote, "path" to path)) { r ->
            val out = ArrayList<Entry>()
            JsonReader(r.body!!.charStream()).use { j ->
                j.beginObject()
                while (j.hasNext()) {
                    if (j.nextName() == "items") {
                        j.beginArray()
                        while (j.hasNext()) out.add(readEntry(j))
                        j.endArray()
                    } else j.skipValue()
                }
                j.endObject()
            }
            out
        }

    private fun readEntry(j: JsonReader): Entry {
        var name = ""; var size = 0L; var dir = false; var mod = 0L
        j.beginObject()
        while (j.hasNext()) {
            val n = j.nextName()
            if (j.peek() == JsonToken.NULL) { j.skipValue(); continue }
            when (n) {
                "name" -> name = j.nextString()
                "size" -> size = j.nextLong()
                "isDir" -> dir = j.nextBoolean()
                "modTime" -> mod = parseTime(j.nextString())
                else -> j.skipValue()
            }
        }
        j.endObject()
        return Entry(name, if (dir) 0L else maxOf(size, 0L), dir, mod)
    }

    /** URL for players. Auth is added by the shared client's interceptor. */
    fun streamUrl(remote: String, path: String): String = url("stream", "remote" to remote, "path" to path).toString()

    /** Server-generated thumbnail (WebP). null = not supported for this file type. */
    fun thumbnail(remote: String, path: String, tag: Any): ByteArray? {
        if (!configured()) throw IOException("Server is not configured")
        val req = Request.Builder().url(url("thumbnails/${encodeId(remote, path)}")).tag(tag).build()
        thumbClient.newCall(req).execute().use { r ->
            if (r.code == 415) return null
            if (!r.isSuccessful) throw fail(r)
            val b = r.body ?: return null
            if (b.contentLength() > 2L * 1024 * 1024) return null
            return b.bytes()
        }
    }

    fun download(remote: String, path: String, dest: File, tag: Any, onBytes: (Long) -> Unit = {}) {
        val req = Request.Builder().url(url("stream", "remote" to remote, "path" to path)).tag(tag).build()
        exec(req) { r ->
            r.body!!.byteStream().use { i ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ operations

    private fun post(op: String, body: JSONObject, tag: Any? = null) {
        val b = Request.Builder().url(url("operations/$op"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (tag != null) b.tag(tag)
        exec(b.build()) { it.body?.close() }
    }

    fun mkdir(remote: String, path: String, tag: Any? = null) =
        post("mkdir", JSONObject().put("remote", remote).put("path", path), tag)

    fun rename(remote: String, path: String, name: String, tag: Any? = null) =
        post("rename", JSONObject().put("remote", remote).put("path", path).put("name", name), tag)

    fun delete(remote: String, path: String, isDir: Boolean, tag: Any? = null) =
        post("delete", JSONObject().put("remote", remote).put("path", path).put("isDir", isDir), tag)

    fun copy(remote: String, path: String, destRemote: String, dest: String, tag: Any? = null) =
        post("copy", JSONObject().put("remote", remote).put("path", path).put("destRemote", destRemote).put("dest", dest), tag)

    fun move(remote: String, path: String, destRemote: String, dest: String, tag: Any? = null) =
        post("move", JSONObject().put("remote", remote).put("path", path).put("destRemote", destRemote).put("dest", dest), tag)

    /** Streams a local file to `PUT /operations/upload` (no buffering in memory, keeps the modification time). */
    fun upload(remote: String, path: String, file: File, tag: Any, onBytes: (Long) -> Unit) {
        val max = maxUpload()
        if (max > 0 && file.length() > max) throw IOException("Larger than the server limit (${max / 1048576} MB)")
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { i ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
        val req = Request.Builder()
            .url(url("operations/upload", "remote" to remote, "path" to path, "modtime" to file.lastModified().toString()))
            .put(body).tag(tag).build()
        exec(req, uploadClient) { it.body?.close() }
    }
}

/** Remote names, cached in prefs so the drawer is instant and works offline. */
object Remotes {
    fun cached(ctx: Context): List<String> = Prefs.remotes(ctx)

    fun refresh(ctx: Context, done: (List<String>?) -> Unit) {
        Api.init(ctx)
        if (!Api.configured()) { done(null); return }
        val app = ctx.applicationContext
        Thread {
            val l = try { Api.remotes() } catch (_: Exception) { null }
            if (l != null) Prefs.setRemotes(app, l)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(l) }
        }.start()
    }
}
