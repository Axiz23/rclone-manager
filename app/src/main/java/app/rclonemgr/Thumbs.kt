package app.rclonemgr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thumbnails: memory LRU -> disk cache (cacheDir/thumbs) -> source.
 * Source 1: matching file in the ".thumbnails" folder. Source 2: generated from the original file.
 */
object Thumbs {
    private const val SIZE = 320
    private const val DISK_MAX = 100L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 30L * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val writes = AtomicInteger()

    private val mem = object : LruCache<String, Bitmap>(8 * 1024) {   // ~8 MB
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // newest request first: what is currently on screen gets loaded before items scrolled past
    private val queue = object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val pool = ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, queue).apply { allowCoreThreadTimeOut(true) }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun cancelAll() { queue.clear() }

    fun load(view: ImageView, remote: String, dir: String, e: Entry) {
        val ctx = view.context.applicationContext
        val full = if (dir.isEmpty()) e.name else "$dir/${e.name}"
        val k = md5("$remote:$full|${e.size}")
        view.tag = k
        mem.get(k)?.let { show(view, it); return }
        pool.execute {
            if (view.tag != k) return@execute
            val bmp = fromDisk(ctx, k) ?: produce(ctx, remote, dir, e)?.also { toDisk(ctx, k, it) }
            if (bmp != null) {
                mem.put(k, bmp)
                main.post { if (view.tag == k) show(view, bmp) }
            }
        }
    }

    private fun show(v: ImageView, b: Bitmap) {
        v.setPadding(0, 0, 0, 0)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setImageBitmap(b)
    }

    private fun produce(ctx: Context, remote: String, dir: String, e: Entry): Bitmap? = try {
        var bmp: Bitmap? = null
        val t = e.thumbName
        if (t != null) {
            val td = if (dir.isEmpty()) ".thumbnails" else "$dir/.thumbnails"
            bmp = downloadDecode(ctx, Rc.fileUrl(remote, td, t))
        }
        if (bmp == null && Prefs.generateThumbs(ctx)) {
            val url = Rc.fileUrl(remote, dir, e.name)
            bmp = if (e.kind == Entry.K_VIDEO) videoThumb(url)
            else if (e.size in 0..MAX_IMAGE_BYTES) downloadDecode(ctx, url) else null
        }
        bmp
    } catch (_: Exception) {
        null
    }

    private fun downloadDecode(ctx: Context, url: String): Bitmap? {
        val tmp = File.createTempFile("th", ".tmp", ctx.cacheDir)
        try {
            val c = Rc.get(url)
            try {
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 16 * 1024) } }
            } finally {
                c.disconnect()
            }
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(tmp)) { dec, info, _ ->
                val m = maxOf(info.size.width, info.size.height)
                if (m > SIZE) {
                    val s = SIZE.toFloat() / m
                    dec.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1)
                    )
                }
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } finally {
            tmp.delete()
        }
    }

    /** Video frame through the local HTTP server (only the needed byte ranges are fetched). */
    private fun videoThumb(url: String): Bitmap? {
        if (!RcServer.awaitReady()) return null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(url, mapOf("Authorization" to RcServer.auth))
            val f = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L) ?: return null
            val m = maxOf(f.width, f.height)
            if (m <= SIZE) return f
            val s = SIZE.toFloat() / m
            val out = Bitmap.createScaledBitmap(
                f, (f.width * s).toInt().coerceAtLeast(1), (f.height * s).toInt().coerceAtLeast(1), true
            )
            if (out !== f) f.recycle()
            return out
        } finally {
            r.release()
        }
    }

    private fun fromDisk(ctx: Context, k: String): Bitmap? {
        val f = File(dir(ctx), "$k.webp")
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(f.path) ?: run { f.delete(); null }
    }

    private fun toDisk(ctx: Context, k: String, b: Bitmap) {
        try {
            File(dir(ctx), "$k.webp").outputStream().use { b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
            if (writes.incrementAndGet() % 40 == 0) trim(ctx)
        } catch (_: Exception) {
        }
    }

    private fun trim(ctx: Context) {
        val fs = dir(ctx).listFiles() ?: return
        var total = fs.sumOf { it.length() }
        if (total <= DISK_MAX) return
        for (f in fs.sortedBy { it.lastModified() }) {
            if (total <= DISK_MAX * 8 / 10) break
            total -= f.length(); f.delete()
        }
    }

    fun diskSize(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clear(ctx: Context) {
        mem.evictAll()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
