package app.rclonemgr

import android.content.Context
import java.io.File

/**
 * General-purpose disk cache (in cacheDir, so Android may also reclaim it). The core manages:
 *  - `lists/`  folder listings (shown instantly, then refreshed from the server)
 *  - `files/`  original photos opened in the viewer
 * and Media3 manages `video/` (see [VideoCache]). Thumbnails have their own folder (see [Thumbs]).
 * The limit is `Prefs.cacheMb`, split half files / half video.
 */
object DiskCache {
    fun limit(ctx: Context): Long = Prefs.cacheMb(ctx) * 1024L * 1024L

    fun size(ctx: Context): Long {
        Core.ensureInit(ctx)
        return Core.long("cache.size") + VideoCache.size(ctx)
    }

    fun clear(ctx: Context) {
        Core.ensureInit(ctx)
        Core.run("cache.clear")
        VideoCache.clear(ctx)
    }

    /** The cached listing of a remote folder, or null. */
    fun getList(ctx: Context, remote: String, path: String): List<Entry>? {
        Core.ensureInit(ctx)
        val a = Core.run("cache.list", Core.args("remote" to remote, "path" to path)) as? org.json.JSONArray ?: return null
        return try { Api.entries(a) } catch (_: Exception) { null }
    }

    /** Cache file of a photo original. The key includes size and date, so a changed file is fetched again. */
    fun photoFile(ctx: Context, remote: String, path: String, e: Entry): File {
        Core.ensureInit(ctx)
        val p = Core.string("cache.photo", Core.args("remote" to remote, "path" to path, "size" to e.size, "modified" to e.modified))
        return File(p ?: throw java.io.IOException("Cache is not available"))
    }

    fun touch(f: File) { f.setLastModified(System.currentTimeMillis()) }

    fun trimFiles(ctx: Context) {
        Core.ensureInit(ctx)
        Core.run("cache.trim", Core.args("limit" to limit(ctx) / 2))
    }
}
