package app.rclonemgr

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** One process-wide Media3 disk cache for streamed video (also what makes looping instant). */
object VideoCache {
    private var cache: SimpleCache? = null

    private fun dir(ctx: Context) = File(ctx.cacheDir, "video")

    @Synchronized
    fun get(ctx: Context): SimpleCache = cache ?: SimpleCache(
        dir(ctx),
        LeastRecentlyUsedCacheEvictor(Prefs.cacheMb(ctx) * 1024L * 1024L / 2),
        StandaloneDatabaseProvider(ctx.applicationContext)
    ).also { cache = it }

    @Synchronized
    fun clear(ctx: Context) {
        try { cache?.release() } catch (_: Exception) { }
        cache = null
        dir(ctx).deleteRecursively()
    }

    fun size(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
