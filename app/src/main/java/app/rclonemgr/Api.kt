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

/**
 * Client for the server API. HOW it talks (paths, methods, auth, field names) comes from the [Adapter] profile,
 * which can be edited in the app at runtime. Every method blocks: call it from a worker thread.
 */
object Api {
    @Volatile private var base = ""
    @Volatile private var token = ""
    @Volatile private var adapter: Adapter = Adapter.default()
    @Volatile private var clientRef: OkHttpClient? = null
    @Volatile private var thumbRef: OkHttpClient? = null
    @Volatile private var uploadRef: OkHttpClient? = null
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        ClientLog.init(app)
        base = Prefs.serverUrl(app)
        token = Prefs.token(app)
        val text = Prefs.adapter(app)
        adapter = (if (text != null) try { Adapter.parse(text) } catch (_: Exception) { null } else null) ?: Adapter.default()
    }

    fun configured() = base.isNotEmpty() && token.isNotEmpty()
    fun maxUpload(): Long = if (::app.isInitialized) Prefs.maxUpload(app) else 0L
    fun adapterJson(): String = (adapter.raw).toString(2)
    fun adapterName(): String = adapter.name

    /** Validates and stores a new adapter profile; takes effect immediately (no restart, no rebuild). */
    fun setAdapter(ctx: Context, text: String?) {
        val a = if (text == null) Adapter.default() else Adapter.parse(text)
        Prefs.setAdapter(ctx.applicationContext, text)
        adapter = a
        clientRef = null; thumbRef = null; uploadRef = null   // timeouts / auth may have changed
    }

    private fun build(read: Long): OkHttpClient {
        val a = adapter
        return OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .connectTimeout(a.seconds("connect", 15), TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .writeTimeout(a.seconds("write", 120), TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val b = chain.request().newBuilder()
                for ((k, v) in adapter.headers) b.header(k, v)
                adapter.authHeader(token)?.let { b.header(it.first, it.second) }
                chain.proceed(b.build())
            })
            .build()
    }

    /** One shared client: HTTP/2 multiplexing over TLS makes thumbnail grids cheap. */
    val client: OkHttpClient
        @Synchronized get() = clientRef ?: build(adapter.seconds("read", 60)).also { clientRef = it }
    private val thumbClient: OkHttpClient
        @Synchronized get() = thumbRef ?: client.newBuilder().readTimeout(adapter.seconds("thumbnail", 120), TimeUnit.SECONDS).build().also { thumbRef = it }
    private val uploadClient: OkHttpClient
        @Synchronized get() = uploadRef ?: client.newBuilder().readTimeout(adapter.seconds("upload", 900), TimeUnit.SECONDS).build().also { uploadRef = it }

    // ------------------------------------------------------------------ plumbing

    /** Builds the request for an adapter endpoint, filling {placeholders}. */
    private fun req(key: String, vars: Map<String, Any?> = emptyMap(), tag: Any? = null, body: RequestBody? = null): Request {
        val a = adapter
        val ep = a.endpoint(key)
        val root = base.toHttpUrlOrNull() ?: throw IOException("Invalid server URL")
        val path = listOf(a.prefix, Adapter.sub(ep.path, vars, true)).filter { it.isNotEmpty() }.joinToString("/")
        val ub = root.newBuilder().addPathSegments(path)
        for ((k, v) in ep.query) ub.addQueryParameter(k, Adapter.sub(v, vars, false))
        if (a.authType == "query") ub.addQueryParameter(a.authName, token)
        val rb = Request.Builder().url(ub.build())
        if (tag != null) rb.tag(tag)
        val payload: RequestBody? = body ?: ep.body?.let {
            Adapter.fill(it, vars).toString().toRequestBody("application/json".toMediaType())
        }
        when {
            ep.method == "GET" || ep.method == "HEAD" -> rb.method(ep.method, null)
            payload != null -> rb.method(ep.method, payload)
            else -> rb.method(ep.method, ByteArray(0).toRequestBody(null))
        }
        return rb.build()
    }

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
        try {
            c.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw fail(r)
                return block(r)
            }
        } catch (e: IOException) {
            if (!isCanceled(e)) ClientLog.w("Api", "${req.method} ${req.url.encodedPath} failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    fun cancel(tag: Any) {
        val d = client.dispatcher
        for (c in d.queuedCalls() + d.runningCalls()) if (c.request().tag() == tag) c.cancel()
    }

    fun isCanceled(e: Exception) = e is IOException && e.message == "Canceled"

    fun parseTime(s: String): Long =
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }

    /** base64url("remote:path"), no padding (the id format of the default adapter). */
    fun encodeId(remote: String, path: String): String =
        Base64.encodeToString("$remote:$path".toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun vars(remote: String, path: String = "", vararg more: Pair<String, Any?>): Map<String, Any?> =
        mapOf<String, Any?>("remote" to remote, "path" to path, "id" to encodeId(remote, path)) + more

    // ------------------------------------------------------------------ setup

    /** Saves URL + token only if the server answers the settings endpoint with them. */
    fun tryConfigure(ctx: Context, url: String, tok: String): JSONObject {
        val ob = base; val ot = token
        base = url.trim().trimEnd('/'); token = tok.trim()
        try {
            val s = settings()
            Prefs.setServer(ctx, base, token)
            Prefs.setMaxUpload(ctx, s.optLong(adapter.field("settingsMaxUpload", "maxUploadBytes"), 0L))
            Prefs.setRemotes(ctx, names(s, adapter.field("settingsRemotes", "remotes")))
            return s
        } catch (e: Exception) {
            base = ob; token = ot
            throw e
        }
    }

    private fun names(s: JSONObject, key: String): List<String> {
        val a = s.optJSONArray(key) ?: return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    fun settings(): JSONObject = exec(req("settings")) { JSONObject(it.body!!.string()) }

    fun remotes(): List<String> = exec(req("remotes")) { r ->
        names(JSONObject(r.body!!.string()), adapter.field("remotesList", "remotes"))
    }

    // ------------------------------------------------------------------ browse

    /** Folder listing; also written to the disk cache so the next visit shows it instantly. */
    fun list(remote: String, path: String): List<Entry> {
        val a = adapter
        val out = exec(req("list", vars(remote, path))) { r ->
            val items = a.field("listItems", "items")
            val list = ArrayList<Entry>()
            JsonReader(r.body!!.charStream()).use { j ->
                j.beginObject()
                while (j.hasNext()) {
                    if (j.nextName() == items) {
                        j.beginArray()
                        while (j.hasNext()) list.add(readEntry(j, a))
                        j.endArray()
                    } else j.skipValue()
                }
                j.endObject()
            }
            list
        }
        try { DiskCache.putList(app, remote, path, out) } catch (_: Exception) { }
        return out
    }

    private fun readEntry(j: JsonReader, a: Adapter): Entry {
        val fn = a.field("name", "name"); val fs = a.field("size", "size")
        val fd = a.field("isDir", "isDir"); val ft = a.field("modTime", "modTime")
        val fmt = a.field("timeFormat", "iso")
        var name = ""; var size = 0L; var dir = false; var mod = 0L
        j.beginObject()
        while (j.hasNext()) {
            val n = j.nextName()
            if (j.peek() == JsonToken.NULL) { j.skipValue(); continue }
            when (n) {
                fn -> name = j.nextString()
                fs -> size = j.nextLong()
                fd -> dir = j.nextBoolean()
                ft -> mod = when (fmt) {
                    "epochMs" -> j.nextLong()
                    "epochSec" -> j.nextLong() * 1000
                    else -> parseTime(j.nextString())
                }
                else -> j.skipValue()
            }
        }
        j.endObject()
        return Entry(name, if (dir) 0L else maxOf(size, 0L), dir, mod)
    }

    /** URL for players. Auth is added by the shared client (header) or is part of the URL (query). */
    fun streamUrl(remote: String, path: String): String = req("stream", vars(remote, path)).url.toString()

    /** Server-generated thumbnail (WebP). null = not supported for this file type. */
    fun thumbnail(remote: String, path: String, tag: Any): ByteArray? {
        if (!configured()) throw IOException("Server is not configured")
        val rq = req("thumbnail", vars(remote, path), tag)
        try {
            thumbClient.newCall(rq).execute().use { r ->
                if (r.code == 415) return null
                if (!r.isSuccessful) throw fail(r)
                val b = r.body ?: return null
                if (b.contentLength() > 2L * 1024 * 1024) return null
                return b.bytes()
            }
        } catch (e: IOException) {
            if (!isCanceled(e)) ClientLog.w("Api", "thumbnail $path failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    fun download(remote: String, path: String, dest: File, tag: Any, onBytes: (Long) -> Unit = {}) {
        exec(req("stream", vars(remote, path), tag)) { r ->
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

    private fun call(key: String, v: Map<String, Any?>, tag: Any?) {
        exec(req(key, v, tag)) { it.body?.close() }
    }

    fun mkdir(remote: String, path: String, tag: Any? = null) = call("mkdir", vars(remote, path), tag)

    fun rename(remote: String, path: String, name: String, tag: Any? = null) =
        call("rename", vars(remote, path, "name" to name), tag)

    fun delete(remote: String, path: String, isDir: Boolean, tag: Any? = null) =
        call("delete", vars(remote, path, "isDir" to isDir), tag)

    fun copy(remote: String, path: String, destRemote: String, dest: String, tag: Any? = null) =
        call("copy", vars(remote, path, "destRemote" to destRemote, "dest" to dest), tag)

    fun move(remote: String, path: String, destRemote: String, dest: String, tag: Any? = null) =
        call("move", vars(remote, path, "destRemote" to destRemote, "dest" to dest), tag)

    /** Streams a local file as the request body (no buffering in memory, keeps the modification time). */
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
        exec(req("upload", vars(remote, path, "modtime" to file.lastModified()), tag, body), uploadClient) { it.body?.close() }
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
