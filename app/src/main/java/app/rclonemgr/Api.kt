package app.rclonemgr

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Kotlin face of the server API client, which lives in the Rust core. HOW it talks (paths, methods, auth, field
 * names) comes from the adapter profile, which can be edited in the app at runtime.
 * Every call blocks: use it from a worker thread.
 */
object Api {
    private lateinit var app: Context

    /** Applies the saved server settings to the core. Cheap; called whenever a screen or service starts. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        Core.ensureInit(app)
        ClientLog.init(app)
        Core.run(
            "configure",
            Core.args(
                "url" to Prefs.serverUrl(app),
                "token" to Prefs.token(app),
                "adapter" to normalized(Prefs.adapter(app)),
                "maxUpload" to Prefs.maxUpload(app),
                "tz" to Core.tzSeconds()
            )
        )
    }

    /**
     * The core parses strict JSON, while the editor has always accepted org.json's lenient syntax. Re-serialising
     * here keeps every adapter that was saved before still valid.
     */
    private fun normalized(text: String?): String? = try {
        text?.let { JSONObject(it).toString() }
    } catch (_: JSONException) {
        null
    }

    fun configured(): Boolean = Core.bool("configured")

    fun adapterJson(): String = (Core.run("adapter.info") as JSONObject).getString("json")

    fun adapterName(): String = (Core.run("adapter.info") as JSONObject).getString("name")

    /** Validates and stores a new adapter profile (null restores the default); applies immediately. */
    fun setAdapter(ctx: Context, text: String?) {
        val json = try {
            text?.let { JSONObject(it).toString() }
        } catch (e: JSONException) {
            throw AdapterException("Invalid JSON: ${e.message}")
        }
        Core.run("adapter.set", Core.args("text" to json))
        Prefs.setAdapter(ctx.applicationContext, text)
    }

    /** Saves URL + token only if the server answers the settings endpoint with them. */
    fun tryConfigure(ctx: Context, url: String, tok: String): JSONObject {
        val r = Core.run("server.connect", Core.args("url" to url, "token" to tok)) as JSONObject
        Prefs.setServer(ctx, url.trim().trimEnd('/'), tok.trim())
        Prefs.setMaxUpload(ctx, r.optLong("maxUpload", 0L))
        Prefs.setRemotes(ctx, strings(r.optJSONArray("remotes")))
        return r
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    fun remotes(): List<String> = strings(Core.array("remotes"))

    /** Folder listing; the core also writes it to the disk cache. */
    fun list(remote: String, path: String): List<Entry> =
        entries(Core.array("list", Core.args("remote" to remote, "path" to path)))

    /** Parses the core's entry format ({n, s, d, m}), which is also the disk-cache format. */
    fun entries(a: JSONArray): List<Entry> = List(a.length()) {
        val o = a.getJSONObject(it)
        Entry(o.getString("n"), o.getLong("s"), o.getBoolean("d"), o.getLong("m"))
    }

    /** URL for players; auth is added by [playerClient] (header) or is part of the URL (query auth). */
    fun streamUrl(remote: String, path: String): String =
        Core.string("streamUrl", Core.args("remote" to remote, "path" to path)) ?: throw IOException("No stream URL")

    fun download(remote: String, path: String, dest: File, tag: Any, cb: ((Long) -> Unit)? = null) {
        Core.run(
            "download",
            Core.args("remote" to remote, "path" to path, "dest" to dest.path, "tag" to tag.toString()),
            object : CoreListener() {
                override fun onBytes(n: Long) { cb?.invoke(n) }
            }
        )
    }

    /** Aborts every call currently running under [tag]. */
    fun cancel(tag: Any) {
        Core.run("cancel", Core.args("tag" to tag.toString()))
    }

    fun isCanceled(e: Exception) = e is IOException && e.message == "Canceled"

    // ------------------------------------------------------------------ video player

    private var playerSig = ""
    private var playerRef: OkHttpClient? = null

    /**
     * HTTP client for Media3, which needs its own data source. It sends the same headers (adapter headers + auth)
     * and uses the same timeouts as the core, so the server sees identical requests. Rebuilt when they change.
     */
    @Synchronized
    fun playerClient(): OkHttpClient {
        val cfg = Core.run("player") as JSONObject
        val sig = cfg.toString()
        playerRef?.let { if (sig == playerSig) return it }
        val h = cfg.getJSONObject("headers")
        val headers = h.keys().asSequence().associateWith { h.getString(it) }
        return OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .connectTimeout(cfg.getLong("connect"), TimeUnit.SECONDS)
            .readTimeout(cfg.getLong("read"), TimeUnit.SECONDS)
            .writeTimeout(cfg.getLong("write"), TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val b = chain.request().newBuilder()
                for ((k, v) in headers) b.header(k, v)
                chain.proceed(b.build())
            })
            .build()
            .also { playerRef = it; playerSig = sig }
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
