package app.rclonemgr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thumbnails: memory LRU -> disk cache (cacheDir/thumbs) -> ".thumbnails/<file name>.webp" next to the file
 * -> generated from the original file. The first two steps run on a wide pool (tiny files), generation from a
 * remote runs on a narrow pool (it downloads the original).
 */
object Thumbs {
    private const val SIZE = 320
    private const val DISK_MAX = 100L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 30L * 1024 * 1024
    private const val MAX_THUMB_BYTES = 2L * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val writes = AtomicInteger()

    private val mem = object : LruCache<String, Bitmap>(8 * 1024) {   // ~8 MB
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // newest request first: what is on screen is loaded before items that were scrolled past
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val fastQ = Lifo()
    private val imgQ = Lifo()
    private val vidQ = Lifo()
    private val fast = ThreadPoolExecutor(4, 4, 20, TimeUnit.SECONDS, fastQ).apply { allowCoreThreadTimeOut(true) }
    // photos and videos have their own pools: a video frame can take minutes and must not block photos
    private val slowImg = ThreadPoolExecutor(3, 3, 20, TimeUnit.SECONDS, imgQ).apply { allowCoreThreadTimeOut(true) }
    private val slowVid = ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, vidQ).apply { allowCoreThreadTimeOut(true) }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun cancelAll() { fastQ.clear(); imgQ.clear(); vidQ.clear() }

    fun load(view: ImageView, loc: Loc, e: Entry, thumbDir: Boolean) {
        val ctx = view.context.applicationContext
        val k = md5("${loc.remote}:${loc.path}/${e.name}|${e.size}|${e.modified}")
        view.tag = k
        mem.get(k)?.let { show(view, it); return }
        fast.execute {
            if (view.tag != k) return@execute
            var bmp = fromDisk(ctx, k)
            if (bmp == null && thumbDir && Prefs.useThumbFolder(ctx)) bmp = fromThumbFolder(ctx, loc, e, k)
            if (bmp == null && Prefs.generateThumbs(ctx)) {
                if (loc.remote == null) {
                    bmp = genLocal(File(loc.path, e.name), e)
                    if (bmp != null) toDisk(ctx, k, bmp)
                } else {
                    val job = Runnable {
                        if (view.tag == k) {
                            // big JPEG: show the tiny EXIF preview first (needs only the first bytes of the file)
                            if (e.kind == Entry.K_IMAGE && (e.ext == "jpg" || e.ext == "jpeg") && e.size > 1_000_000L) {
                                val pv = exifPreview(loc, e)
                                if (pv != null) main.post { if (view.tag == k) show(view, pv) }
                            }
                            if (view.tag == k) {
                                val b = genRemote(ctx, loc, e)
                                if (b != null) { toDisk(ctx, k, b); deliver(view, k, b) }
                            }
                        }
                    }
                    if (e.kind == Entry.K_VIDEO) slowVid.execute(job) else slowImg.execute(job)
                    return@execute
                }
            }
            if (bmp != null) deliver(view, k, bmp)
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

    /** `.thumbnails/<file name>.webp` in the same directory as the file. */
    private fun fromThumbFolder(ctx: Context, loc: Loc, e: Entry, k: String): Bitmap? = try {
        val name = e.name + ".webp"
        val bytes: ByteArray? = if (loc.remote == null) {
            val f = File(File(loc.path, ".thumbnails"), name)
            if (f.isFile && f.length() in 1L..MAX_THUMB_BYTES) f.readBytes() else null
        } else {
            val d = if (loc.path.isEmpty()) ".thumbnails" else "${loc.path}/.thumbnails"
            Rc.readBytes(Rc.fileUrl(loc.remote, d, name), MAX_THUMB_BYTES)
        }
        if (bytes == null) null else {
            File(dir(ctx), "$k.webp").writeBytes(bytes)   // keep the raw bytes in the cache, no re-encoding
            decodeBytes(bytes)
        }
    } catch (_: Exception) {
        null
    }

    private fun decodeBytes(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= SIZE) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Low-resolution preview from the EXIF thumbnail (not cached; replaced by the real thumbnail later). */
    private fun exifPreview(loc: Loc, e: Entry): Bitmap? = try {
        val head = Rc.readHead(Rc.fileUrl(loc.remote!!, loc.path, e.name), 192 * 1024)
        val ex = ExifInterface(ByteArrayInputStream(head))
        val tb = ex.thumbnailBytes
        if (tb == null) null else {
            var bmp = BitmapFactory.decodeByteArray(tb, 0, tb.size)
            val deg = when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
            if (bmp != null && deg != 0)
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            bmp
        }
    } catch (_: Exception) {
        null
    }

    private fun genLocal(f: File, e: Entry): Bitmap? = try {
        if (e.kind == Entry.K_VIDEO) ThumbnailUtils.createVideoThumbnail(f, Size(SIZE, SIZE), null)
        else decodeFile(f)
    } catch (_: Exception) {
        null
    }

    private fun genRemote(ctx: Context, loc: Loc, e: Entry): Bitmap? = try {
        val url = Rc.fileUrl(loc.remote!!, loc.path, e.name)
        if (e.kind == Entry.K_VIDEO) videoThumb(url)
        else if (e.size in 0L..MAX_IMAGE_BYTES) downloadDecode(ctx, url)
        else null
    } catch (_: Exception) {
        null
    }

    private fun decodeFile(f: File): Bitmap? = try {
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
            return decodeFile(tmp)
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
