package app.rclonemgr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class PageVH(v: View) : RecyclerView.ViewHolder(v) {
    val preview: ImageView = v.findViewById(R.id.preview)
    val iv: ZoomImageView = v.findViewById(R.id.iv)
    val progress: View = v.findViewById(R.id.progress)
    var token = 0
    @Volatile var conn: Any? = null   // tag of the in-flight download
}

/** Photo viewer: swipe between photos (the page follows the finger), neighbours are preloaded. */
class ImageActivity : BaseActivity() {
    private lateinit var pager: ViewPager2
    private lateinit var bar: View
    private lateinit var tb: MaterialToolbar
    private lateinit var adapter: PageAdapter
    private var remote = ""
    private var dir = ""
    private var local = false
    private var items: List<Entry> = emptyList()
    private var index = 0
    private var barsShown = true
    private var slideshow = false
    private var pending: ((Loc) -> Unit)? = null
    private val pool = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())

    private val picker = registerForActivityResult(MediaActions.PickFolder()) { l ->
        if (l != null) { pending?.invoke(l) }
        pending = null
    }

    private val slideRun = object : Runnable {
        override fun run() {
            if (!slideshow) return
            if (index + 1 < items.size) { pager.setCurrentItem(index + 1, true); ui.postDelayed(this, 3500) }
            else stopSlideshow()
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_image)
        bar = findViewById(R.id.bar)
        applyOverlayInsets(findViewById(R.id.root), bar, null)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        remote = intent.getStringExtra("remote") ?: run { finish(); return }
        local = remote.isEmpty()
        dir = intent.getStringExtra("dir") ?: ""
        items = MediaHolder.images
        val start = intent.getIntExtra("index", 0)
        if (items.isEmpty() || start !in items.indices) { finish(); return }

        tb = findViewById(R.id.toolbar)
        tb.setTitleTextColor(Color.WHITE); tb.setSubtitleTextColor(Color.LTGRAY)
        tb.setNavigationIconTint(Color.WHITE)
        tb.setNavigationOnClickListener { finish() }
        tb.inflateMenu(R.menu.viewer)
        tb.menu.findItem(R.id.action_more)?.icon?.mutate()?.setTint(Color.WHITE)
        tb.setOnMenuItemClickListener { showMenu(); true }

        pager = findViewById(R.id.pager)
        adapter = PageAdapter()
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.setItemViewCacheSize(0)   // free off-screen bitmaps quickly
        pager.setPageTransformer(CompositePageTransformer().apply {
            addTransformer(MarginPageTransformer(dp(16)))
            addTransformer(ViewPager2.PageTransformer { page, pos ->
                val a = minOf(abs(pos), 1f)
                page.alpha = 1f - 0.35f * a
                val s = 1f - 0.08f * a
                page.scaleX = s
                page.scaleY = s
            })
        })
        pager.setCurrentItem(start, false)
        index = start
        updateTitle()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { index = position; updateTitle() }
        })
    }

    private fun curLoc() = Loc(if (local) null else remote, dir)

    private fun updateTitle() {
        tb.title = items[index].name
        tb.subtitle = "${index + 1} / ${items.size}"
    }

    private fun onPageTap() { if (slideshow) stopSlideshow() else setBars(!barsShown) }

    // ------------------------------------------------------------------ bars (title + system bars)

    private fun setBars(show: Boolean) {
        barsShown = show
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        if (show) { bar.visibility = View.VISIBLE; bar.animate().alpha(1f).setDuration(150).start() }
        else bar.animate().alpha(0f).setDuration(150).withEndAction { if (!barsShown) bar.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        val pm = PopupMenu(this, tb, Gravity.END)
        pm.setForceShowIcon(true)
        val m = pm.menu
        m.add(0, 1, 0, "About").setIcon(R.drawable.ic_info)
        if (local) m.add(0, 2, 1, "Back up now").setIcon(R.drawable.ic_cloud_upload)
        m.add(0, 3, 2, "Move to folder").setIcon(R.drawable.ic_move)
        m.add(0, 4, 3, "Copy to folder").setIcon(R.drawable.ic_copy)
        m.add(0, 5, 4, if (slideshow) "Stop slideshow" else "Slideshow").setIcon(R.drawable.ic_slideshow)
        m.add(0, 6, 5, "Cast").setIcon(R.drawable.ic_cast)
        m.add(0, 7, 6, "Delete file").setIcon(R.drawable.ic_delete)
        pm.setOnMenuItemClickListener { onMenu(it.itemId); true }
        pm.show()
    }

    private fun onMenu(id: Int) {
        val name = items[index].name
        when (id) {
            1 -> MediaActions.about(this, curLoc(), name, null)
            2 -> MediaActions.backup(this, curLoc(), name) { cb -> pending = cb; picker.launch(Unit) }
            3 -> { pending = { d -> MediaActions.copyMove(this, curLoc(), name, d, true) { removeCurrent() } }; picker.launch(Unit) }
            4 -> { pending = { d -> MediaActions.copyMove(this, curLoc(), name, d, false) { } }; picker.launch(Unit) }
            5 -> if (slideshow) stopSlideshow() else startSlideshow()
            6 -> MediaActions.cast(this)
            7 -> MediaActions.delete(this, curLoc(), name) { removeCurrent() }
        }
    }

    private fun startSlideshow() {
        slideshow = true
        setBars(false)
        ui.postDelayed(slideRun, 3500)
    }

    private fun stopSlideshow() {
        slideshow = false
        ui.removeCallbacks(slideRun)
        setBars(true)
    }

    /** After delete / move: drop the current photo from the pager. */
    private fun removeCurrent() {
        val l = items.toMutableList()
        val at = index
        l.removeAt(at)
        items = l
        MediaHolder.images = l
        MediaHolder.names = l.map { it.name }
        if (l.isEmpty()) { finish(); return }
        adapter.notifyItemRemoved(at)
        index = index.coerceAtMost(l.size - 1)
        updateTitle()
    }

    // ------------------------------------------------------------------ pages

    private inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val h = PageVH(layoutInflater.inflate(R.layout.item_image_page, parent, false))
            h.iv.onTap = { onPageTap() }
            return h
        }

        override fun onBindViewHolder(h: PageVH, position: Int) { load(h, items[position]) }

        override fun onViewRecycled(h: PageVH) {
            h.token++
            h.conn?.let { Api.cancel(it) }
            h.iv.animate().cancel()
            h.iv.setImageDrawable(null)
            h.preview.setImageDrawable(null)
        }
    }

    /**
     * Shows the thumbnail first (cache or ".thumbnails", if there is one) as a blurry preview, then cross-fades
     * to the real image once it has been loaded.
     */
    private fun load(h: PageVH, e: Entry) {
        val my = ++h.token
        h.conn?.let { Api.cancel(it) }
        h.iv.animate().cancel()
        h.iv.setImageDrawable(null)
        h.iv.alpha = 1f
        h.preview.setImageDrawable(null)
        h.progress.visibility = View.VISIBLE
        val loc = curLoc()
        pool.execute {
            if (h.token != my) return@execute
            val th = try { Thumbs.peek(applicationContext, loc, e) } catch (_: Exception) { null }
            if (th != null) ui.post { if (h.token == my) h.preview.setImageBitmap(th) }
            var err: String? = null
            val bmp = try { fetch(e, h) } catch (ex: Exception) { err = ex.message ?: ex.javaClass.simpleName; null }
            val msg = err
            ui.post {
                if (h.token == my) {
                    h.progress.visibility = View.GONE
                    if (bmp != null) {
                        h.iv.alpha = 0f
                        h.iv.setBitmapAndReset(bmp)
                        h.iv.animate().alpha(1f).setDuration(250)
                            .withEndAction { if (h.token == my) h.preview.setImageDrawable(null) }.start()
                    } else {
                        Toast.makeText(this, "Cannot load image" + (if (msg != null) ": $msg" else ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun decode(f: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
            val m = maxOf(info.size.width, info.size.height)
            if (m > 1600) {
                val s = 1600f / m
                dec.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1)
                )
            }
        }

    /** Device: decode directly. Server: download to a temp file first. Always down-sampled (max 2048 px). */
    private fun fetch(e: Entry, h: PageVH): Bitmap? {
        if (local) return decode(File(dir, e.name))
        val rp = Loc(remote, dir).child(e.name).path
        val f = DiskCache.photoFile(applicationContext, remote, rp, e)
        if (f.exists() && f.length() > 0) {
            DiskCache.touch(f)            // already on disk: no network at all
        } else {
            val part = File(f.path + ".part")
            var last: Exception? = null
            for (attempt in 0..1) {      // one retry: the server may hiccup under parallel loads
                val tag = Any()
                h.conn = tag
                try {
                    Api.download(remote, rp, part, tag)
                    if (!part.renameTo(f)) throw java.io.IOException("Cannot store the photo")
                    last = null
                    break
                } catch (ex: Exception) {
                    part.delete()
                    last = ex
                    if (Api.isCanceled(ex)) throw ex
                    try { Thread.sleep(700) } catch (_: InterruptedException) { throw ex }
                }
            }
            last?.let { throw it }
            DiskCache.trimFiles(applicationContext)
        }
        return decode(f)
    }

    override fun onDestroy() {
        ui.removeCallbacks(slideRun)
        pool.shutdownNow()
        super.onDestroy()
    }
}
