package app.rclonemgr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * General-purpose disk cache (cacheDir, so Android may also reclaim it):
 *  - `lists/`  folder listings (shown instantly, then refreshed from the server)
 *  - `files/`  original photos opened in the viewer
 *  - `video/`  Media3 SimpleCache for streamed video (see VideoCache)
 * Thumbnails keep their own folder (`thumbs/`, see Thumbs). The limit is Prefs.cacheMb, split half files / half video.
 */
object DiskCache {
    private fun dir(ctx: Context, name: String) = File(ctx.cacheDir, name).apply { mkdirs() }
    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun limit(ctx: Context): Long = Prefs.cacheMb(ctx) * 1024L * 1024L

    private fun used(d: File) = d.listFiles()?.sumOf { it.length() } ?: 0L

    fun size(ctx: Context): Long =
        used(dir(ctx, "lists")) + used(dir(ctx, "files")) + VideoCache.size(ctx)

    fun clear(ctx: Context) {
        dir(ctx, "lists").listFiles()?.forEach { it.delete() }
        dir(ctx, "files").listFiles()?.forEach { it.delete() }
        VideoCache.clear(ctx)
    }

    private fun trim(d: File, max: Long) {
        val fs = d.listFiles() ?: return
        var total = fs.sumOf { it.length() }
        if (total <= max) return
        for (f in fs.sortedBy { it.lastModified() }) {
            if (total <= max * 8 / 10) break
            total -= f.length(); f.delete()
        }
    }

    // ---- folder listings
    private fun listFile(ctx: Context, remote: String, path: String) = File(dir(ctx, "lists"), md5("$remote:$path") + ".json")

    fun putList(ctx: Context, remote: String, path: String, l: List<Entry>) {
        val a = JSONArray()
        for (e in l) a.put(JSONObject().put("n", e.name).put("s", e.size).put("d", e.isDir).put("m", e.modified))
        listFile(ctx, remote, path).writeText(a.toString())
        if (Math.random() < 0.05) trim(dir(ctx, "lists"), 20L * 1024 * 1024)
    }

    fun getList(ctx: Context, remote: String, path: String): List<Entry>? = try {
        val f = listFile(ctx, remote, path)
        if (!f.exists()) null else {
            val a = JSONArray(f.readText())
            List(a.length()) { val o = a.getJSONObject(it); Entry(o.getString("n"), o.getLong("s"), o.getBoolean("d"), o.getLong("m")) }
        }
    } catch (_: Exception) {
        null
    }

    // ---- photo originals (key includes size + modified, so a changed file is fetched again)
    fun photoFile(ctx: Context, remote: String, path: String, e: Entry) =
        File(dir(ctx, "files"), md5("$remote:$path|${e.size}|${e.modified}"))

    fun touch(f: File) { f.setLastModified(System.currentTimeMillis()) }
    fun trimFiles(ctx: Context) = trim(dir(ctx, "files"), limit(ctx) / 2)
}
