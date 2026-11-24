package app.rclonemgr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thumbnails.
 *  - Remote files: memory LRU -> disk cache -> `GET /thumbnails/{id}` (the server generates, stores and serves them).
 *    The app never downloads originals to make thumbnails.
 *  - Device files: memory LRU -> disk cache -> decoded from the local file (there is no server copy to ask for).
 */
object Thumbs {
    const val TAG = "thumbs"
    private const val SIZE = 320
    private const val DISK_MAX = 100L * 1024 * 1024
    private const val RETRY_AFTER_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val writes = AtomicInteger()
    private val failed = HashMap<String, Long>()

    private val mem = object : LruCache<String, Bitmap>(6 * 1024) {   // ~6 MB
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // newest request first: what is on screen is loaded before items that were scrolled past
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val netQ = Lifo()
    private val localQ = Lifo()
    private val net = ThreadPoolExecutor(4, 4, 20, TimeUnit.SECONDS, netQ).apply { allowCoreThreadTimeOut(true) }
    private val local = ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, localQ).apply { allowCoreThreadTimeOut(true) }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun keyOf(loc: Loc, e: Entry) = md5("${loc.remote}:${loc.path}/${e.name}|${e.size}|${e.modified}")

    fun cancelAll() {
        netQ.clear(); localQ.clear()
        Api.cancel(TAG)
    }

    @Synchronized private fun recentlyFailed(k: String): Boolean {
        val t = failed[k] ?: return false
        if (System.currentTimeMillis() - t > RETRY_AFTER_MS) { failed.remove(k); return false }
        return true
    }
    @Synchronized private fun markFailed(k: String) { failed[k] = System.currentTimeMillis() }

    /** Thumbnail that is already cached (memory / disk). Never touches the network, so it cannot delay the full image. */
    fun peek(ctx: Context, loc: Loc, e: Entry): Bitmap? {
        val k = keyOf(loc, e)
        mem.get(k)?.let { return it }
        return fromDisk(ctx, k)
    }

    fun load(view: ImageView, loc: Loc, e: Entry) {
        val ctx = view.context.applicationContext
        val k = keyOf(loc, e)
        view.tag = k
        mem.get(k)?.let { show(view, it); return }
        if (loc.remote == null) {
            local.execute {
                if (view.tag != k) return@execute
                var bmp = fromDisk(ctx, k)
                if (bmp == null) {
                    bmp = genLocal(File(loc.path, e.name), e)
                    if (bmp != null) toDisk(ctx, k, bmp)
                }
                if (bmp != null) deliver(view, k, bmp)
            }
        } else {
            net.execute {
                if (view.tag != k) return@execute
                var bmp = fromDisk(ctx, k)
                if (bmp == null && !recentlyFailed(k)) {
                    bmp = try {
                        fetchRemote(ctx, loc, e, k)
                    } catch (ex: Exception) {
                        if (!Api.isCanceled(ex)) markFailed(k)
                        null
                    }
                    if (bmp == null && view.tag == k) markFailed(k)
                }
                if (bmp != null) deliver(view, k, bmp)
            }
        }
    }

    private fun deliver(view: ImageView, k: String, b: Bitmap) {
        mem.put(k, b)
        main.post { if (view.tag == k) show(view, b) }
    }

    private fun show(v: ImageView, b: Bitmap) {
        v.imageTintList = null
        v.setPadding(0, 0, 0, 0)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setImageBitmap(b)
    }

    /** `GET /thumbnails/{id}`; the raw WebP bytes are cached as-is (no re-encoding). */
    private fun fetchRemote(ctx: Context, loc: Loc, e: Entry, k: String): Bitmap? {
        val bytes = Api.thumbnail(loc.remote!!, loc.child(e.name).path, TAG) ?: return null
        try {
            File(dir(ctx), "$k.webp").writeBytes(bytes)
            if (writes.incrementAndGet() % 40 == 0) trim(ctx)
        } catch (_: Exception) {
        }
        return decodeBytes(bytes)
    }

    private fun decodeBytes(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= SIZE) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun genLocal(f: File, e: Entry): Bitmap? = try {
        if (e.kind == Entry.K_VIDEO) ThumbnailUtils.createVideoThumbnail(f, Size(SIZE, SIZE), null)
        else decodeFile(f)
    } catch (_: Exception) {
        null
    }

    private fun decodeFile(f: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
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
        synchronized(this) { failed.clear() }
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
