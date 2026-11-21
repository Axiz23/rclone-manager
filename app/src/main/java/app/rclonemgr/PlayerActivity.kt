package app.rclonemgr

import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

class PlayerActivity : BaseActivity() {
    private lateinit var vv: VideoView
    private lateinit var progress: View
    private var player: MediaPlayer? = null
    private var acquired = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_player)
        applyInsets(findViewById(R.id.root), findViewById(R.id.bar))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val remote = intent.getStringExtra("remote")
        val dir = intent.getStringExtra("dir") ?: ""
        val name = intent.getStringExtra("name")
        if (remote == null || name == null) { finish(); return }
        val local = remote.isEmpty()
        if (!local) { RcServer.pins++; RcServer.acquire(this); acquired = true }

        vv = findViewById(R.id.vv); progress = findViewById(R.id.progress)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.title = name
        tb.setTitleTextColor(Color.WHITE)
        tb.setNavigationIconTint(Color.WHITE)
        tb.setNavigationOnClickListener { finish() }
        tb.inflateMenu(R.menu.player)
        tb.overflowIcon?.setTint(Color.WHITE)
        tb.menu.findItem(R.id.action_loop).isChecked = Prefs.loop(this)   // global setting, default ON
        tb.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_loop) {
                val v = !it.isChecked
                it.isChecked = v
                Prefs.setLoop(this, v)
                player?.isLooping = v
                true
            } else false
        }

        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setOnPreparedListener { mp ->
            player = mp
            mp.isLooping = Prefs.loop(this)
            progress.visibility = View.GONE
            vv.start()
        }
        vv.setOnErrorListener { _, _, _ ->
            progress.visibility = View.GONE
            Toast.makeText(this, "Cannot play this video", Toast.LENGTH_SHORT).show()
            true
        }

        if (local) {
            vv.setVideoPath(File(dir, name).path)
        } else {
            Thread {
                val ok = RcServer.awaitReady()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (ok) vv.setVideoURI(
                        Uri.parse(Rc.fileUrl(remote, dir, name)), mapOf("Authorization" to RcServer.auth)
                    ) else {
                        progress.visibility = View.GONE
                        Toast.makeText(this, "rclone server did not start", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::vv.isInitialized) vv.pause()
    }

    override fun onDestroy() {
        if (::vv.isInitialized) vv.stopPlayback()
        if (acquired) { RcServer.pins--; RcServer.release() }
        super.onDestroy()
    }
}
