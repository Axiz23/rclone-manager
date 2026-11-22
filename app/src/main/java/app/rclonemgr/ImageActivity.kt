package app.rclonemgr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.Executors

class ImageActivity : BaseActivity() {
    private lateinit var iv: ZoomImageView
    private lateinit var progress: View
    private lateinit var bar: View
    private lateinit var tb: MaterialToolbar
    private var remote = ""
    private var dir = ""
    private var local = false
    private var names: List<String> = emptyList()
    private var index = 0
    private var token = 0
    private var acquired = false
    private var barsShown = true
    private var slideshow = false
    private var pending: ((Loc) -> Unit)? = null
    @Volatile private var conn: HttpURLConnection? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val picker = registerForActivityResult(MediaActions.PickFolder()) { l ->
        if (l != null) { pending?.invoke(l) }
        pending = null
    }

    private val slideRun = object : Runnable {
        override fun run() {
            if (!slideshow) return
            if (index + 1 < names.size) { show(index + 1); ui.postDelayed(this, 3500) } else stopSlideshow()
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
        names = MediaHolder.names
        val start = intent.getIntExtra("index", 0)
        if (names.isEmpty() || start !in names.indices) { finish(); return }
        if (!local) { RcServer.acquire(this); acquired = true }

        iv = findViewById(R.id.iv); progress = findViewById(R.id.progress); tb = findViewById(R.id.toolbar)
        tb.setTitleTextColor(Color.WHITE); tb.setSubtitleTextColor(Color.LTGRAY)
        tb.setNavigationIconTint(Color.WHITE)
        tb.setNavigationOnClickListener { finish() }
        tb.inflateMenu(R.menu.viewer)
        tb.menu.findItem(R.id.action_more)?.icon?.mutate()?.setTint(Color.WHITE)
        tb.setOnMenuItemClickListener { showMenu(); true }
        iv.onSwipe = { d ->
            val n = index + d
            if (n in names.indices) show(n)
        }
        iv.onTap = { if (slideshow) stopSlideshow() else setBars(!barsShown) }
        show(start)
    }

    private fun curLoc() = Loc(if (local) null else remote, dir)

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
        val name = names[index]
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

    /** After delete / move: drop the current image from the list and show the next one. */
    private fun removeCurrent() {
        val l = names.toMutableList()
        l.removeAt(index)
        names = l
        MediaHolder.names = l
        if (l.isEmpty()) finish() else show(index.coerceAtMost(l.size - 1))
    }

    // ------------------------------------------------------------------ loading

    private fun show(i: Int) {
        index = i
        val name = names[i]
        val my = ++token
        conn?.disconnect()
        tb.title = name
        tb.subtitle = "${i + 1} / ${names.size}"
        progress.visibility = View.VISIBLE
        io.execute {
            val bmp = try { fetch(name) } catch (_: Exception) { null }
            ui.post {
                if (my == token) {
                    progress.visibility = View.GONE
                    if (bmp != null) iv.setBitmapAndReset(bmp)
                    else Toast.makeText(this, "Cannot load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decode(f: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
            val m = maxOf(info.size.width, info.size.height)
            if (m > 2048) {
                val s = 2048f / m
                dec.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1)
                )
            }
        }

    /** Local: decode directly. Remote: download to a temp file first. Always down-sampled (max 2048 px). */
    private fun fetch(name: String): Bitmap? {
        if (local) return decode(File(dir, name))
        val tmp = File.createTempFile("view", ".tmp", cacheDir)
        try {
            val c = Rc.get(Rc.fileUrl(remote, dir, name))
            conn = c
            try {
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 32 * 1024) } }
            } finally {
                c.disconnect()
            }
            return decode(tmp)
        } finally {
            tmp.delete()
        }
    }

    override fun onDestroy() {
        token++
        ui.removeCallbacks(slideRun)
        conn?.disconnect()
        io.shutdownNow()
        if (acquired) RcServer.release()
        super.onDestroy()
    }
}
