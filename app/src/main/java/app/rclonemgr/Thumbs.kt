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

/**
 * Thumbnails.
 *  - Remote files: memory LRU -> disk cache -> server thumbnail (the server generates, stores and serves them; the
 *    Rust core fetches them, keeps the disk cache within its limit and backs off after failures).
 *    The app never downloads originals to make thumbnails.
 *  - Device files: memory LRU -> disk cache -> decoded from the local file (there is no server copy to ask for).
 * Bitmap decoding and the in-memory cache stay here because they depend on Android graphics.
 */
object Thumbs {
    const val TAG = "thumbs"
    private const val SIZE = 320

    private val main = Handler(Looper.getMainLooper())

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

    private fun file(ctx: Context, k: String): File {
        Core.ensureInit(ctx)
        return File(Core.dirs.thumbs, "$k.webp")
    }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun keyOf(loc: Loc, e: Entry) = md5("${loc.remote}:${loc.path}/${e.name}|${e.size}|${e.modified}")

    fun cancelAll() {
        netQ.clear(); localQ.clear()
        Api.cancel(TAG)
    }

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
                if (bmp == null) bmp = try { fetchRemote(loc, e, k) } catch (_: Exception) { null }   // the core remembers failures
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

    /** Asks the core for the server thumbnail (stored in the disk cache as raw WebP); null when there is none. */
    private fun fetchRemote(loc: Loc, e: Entry, k: String): Bitmap? {
        val p = Core.string("thumb.fetch", Core.args("remote" to loc.remote, "path" to loc.child(e.name).path, "key" to k, "tag" to TAG))
            ?: return null
        return decodeBytes(File(p).readBytes())
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
        val f = file(ctx, k)
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(f.path) ?: run { f.delete(); null }
    }

    private fun toDisk(ctx: Context, k: String, b: Bitmap) {
        try {
            file(ctx, k).outputStream().use { b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
            Core.run("thumb.wrote")   // the core trims the folder every 40 writes
        } catch (_: Exception) {
        }
    }

    fun diskSize(ctx: Context): Long {
        Core.ensureInit(ctx)
        return Core.long("thumb.size")
    }

    fun clear(ctx: Context) {
        Core.ensureInit(ctx)
        mem.evictAll()
        Core.run("thumb.clear")
    }
}
