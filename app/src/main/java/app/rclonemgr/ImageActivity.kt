package app.rclonemgr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.Executors

class ImageActivity : BaseActivity() {
    private lateinit var iv: ZoomImageView
    private lateinit var progress: View
    private lateinit var tb: MaterialToolbar
    private var remote = ""
    private var dir = ""
    private var names: List<String> = emptyList()
    private var token = 0
    private var acquired = false
    @Volatile private var conn: HttpURLConnection? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_image)
        applyInsets(findViewById(R.id.root), findViewById(R.id.bar))
        remote = intent.getStringExtra("remote") ?: run { finish(); return }
        dir = intent.getStringExtra("dir") ?: ""
        names = MediaHolder.names
        val start = intent.getIntExtra("index", 0)
        if (names.isEmpty() || start !in names.indices) { finish(); return }
        RcServer.acquire(this); acquired = true

        iv = findViewById(R.id.iv); progress = findViewById(R.id.progress); tb = findViewById(R.id.toolbar)
        tb.setTitleTextColor(Color.WHITE); tb.setSubtitleTextColor(Color.LTGRAY)
        tb.setNavigationIconTint(Color.WHITE)
        tb.setNavigationOnClickListener { finish() }
        iv.onSwipe = { d ->
            val n = index + d
            if (n in names.indices) show(n)
        }
        show(start)
    }

    private var index = 0

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

    /** Download to a temp file, then decode down-sampled (max 2048 px) to keep memory low. */
    private fun fetch(name: String): Bitmap? {
        val tmp = File.createTempFile("view", ".tmp", cacheDir)
        try {
            val c = Rc.get(Rc.fileUrl(remote, dir, name))
            conn = c
            try {
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 32 * 1024) } }
            } finally {
                c.disconnect()
            }
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(tmp)) { dec, info, _ ->
                val m = maxOf(info.size.width, info.size.height)
                if (m > 2048) {
                    val s = 2048f / m
                    dec.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1)
                    )
                }
            }
        } finally {
            tmp.delete()
        }
    }

    override fun onDestroy() {
        token++
        conn?.disconnect()
        io.shutdownNow()
        if (acquired) RcServer.release()
        super.onDestroy()
    }
}
