package app.rclonemgr

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Video player with Google-Photos-like controls. Tap the video to hide / show the title bar and the controls. */
class PlayerActivity : BaseActivity() {
    private lateinit var root: View
    private lateinit var vv: VideoView
    private lateinit var progress: View
    private lateinit var bar: View
    private lateinit var controls: View
    private lateinit var tb: MaterialToolbar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnVolume: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeTv: TextView

    private var player: MediaPlayer? = null
    private var acquired = false
    private var local = false
    private var remote = ""
    private var dir = ""
    private var videos: List<String> = emptyList()
    private var index = 0
    private var speed = 1f
    private var muted = false
    private var slideshow = false
    private var dragging = false
    private var barsShown = true
    private var pending: ((Loc) -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private val picker = registerForActivityResult(MediaActions.PickFolder()) { l ->
        if (l != null) { pending?.invoke(l) }
        pending = null
    }
    private val tick = object : Runnable {
        override fun run() { updateProgress(); ui.postDelayed(this, 250) }
    }
    private val autoHide = Runnable { if (vv.isPlaying) setBars(false) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.root); bar = findViewById(R.id.bar); controls = findViewById(R.id.controls)
        applyInsets(root, bar)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        remote = intent.getStringExtra("remote") ?: run { finish(); return }
        dir = intent.getStringExtra("dir") ?: ""
        val name = intent.getStringExtra("name") ?: run { finish(); return }
        local = remote.isEmpty()
        videos = MediaHolder.videos.takeIf { name in it } ?: listOf(name)
        index = videos.indexOf(name)
        if (!local) { RcServer.pins++; RcServer.acquire(this); acquired = true }

        vv = findViewById(R.id.vv); progress = findViewById(R.id.progress)
        btnPlay = findViewById(R.id.btnPlay); btnVolume = findViewById(R.id.btnVolume)
        seek = findViewById(R.id.seek); timeTv = findViewById(R.id.time)

        tb = findViewById(R.id.toolbar)
        tb.setTitleTextColor(Color.WHITE)
        tb.setNavigationIconTint(Color.WHITE)
        tb.setNavigationOnClickListener { finish() }
        tb.inflateMenu(R.menu.viewer)
        tb.menu.findItem(R.id.action_more)?.icon?.mutate()?.setTint(Color.WHITE)
        tb.setOnMenuItemClickListener { showMenu(); true }

        root.setOnClickListener { setBars(!barsShown) }
        btnPlay.setOnClickListener {
            if (vv.isPlaying) vv.pause() else vv.start()
            updatePlayIcon(); setBars(true)
        }
        btnVolume.setOnClickListener {
            muted = !muted
            player?.let { applyVolume(it) }
            updateVolIcon()
        }
        findViewById<View>(R.id.btnSavePhoto).setOnClickListener { savePhoto() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) timeTv.text = fmt(p) + " / " + fmt(vv.duration)
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true; ui.removeCallbacks(autoHide) }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                vv.seekTo(s.progress)
                setBars(true)
            }
        })

        vv.setOnPreparedListener { mp ->
            player = mp
            mp.isLooping = Prefs.loop(this) && !slideshow
            applyVolume(mp)
            if (speed != 1f) applySpeed(mp)
            progress.visibility = View.GONE
            seek.max = maxOf(1, vv.duration)
            vv.start()
            updatePlayIcon()
            setBars(true)
        }
        vv.setOnCompletionListener {
            updatePlayIcon()
            if (slideshow && index + 1 < videos.size) play(index + 1) else setBars(true)
        }
        vv.setOnErrorListener { _, _, _ ->
            progress.visibility = View.GONE
            toast("Cannot play this video")
            true
        }
        vv.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) progress.visibility = View.VISIBLE
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) progress.visibility = View.GONE
            false
        }
        updateVolIcon()
        play(index)
        ui.post(tick)
    }

    private fun curLoc() = Loc(if (local) null else remote, dir)

    private fun play(i: Int) {
        index = i
        val name = videos[i]
        tb.title = name
        tb.subtitle = if (videos.size > 1) "${i + 1} / ${videos.size}" else null
        progress.visibility = View.VISIBLE
        seek.progress = 0
        seek.secondaryProgress = 0
        timeTv.text = "0:00 / 0:00"
        if (local) {
            vv.setVideoPath(File(dir, name).path)
        } else {
            Thread {
                val ok = RcServer.awaitReady()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (ok) vv.setVideoURI(Uri.parse(Rc.fileUrl(remote, dir, name)), mapOf("Authorization" to RcServer.auth))
                    else { progress.visibility = View.GONE; toast("rclone server did not start") }
                }
            }.start()
        }
    }

    // ------------------------------------------------------------------ bars (title + controls + system bars)

    private fun setBars(show: Boolean) {
        barsShown = show
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        fade(bar, show)
        fade(controls, show)
        ui.removeCallbacks(autoHide)
        if (show) ui.postDelayed(autoHide, 3500)
    }

    private fun fade(v: View, show: Boolean) {
        if (show) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(150).start() }
        else v.animate().alpha(0f).setDuration(150).withEndAction { if (!barsShown) v.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------ playback state

    private fun updateProgress() {
        if (!::vv.isInitialized || dragging) return
        val dur = vv.duration
        if (dur <= 0) return
        if (seek.max != dur) seek.max = dur
        val pos = vv.currentPosition.coerceIn(0, dur)
        seek.progress = pos
        // buffered part of the video (lighter bar); local files are fully available
        val buffered = if (local) dur else (dur.toLong() * vv.bufferPercentage / 100).toInt()
        seek.secondaryProgress = buffered.coerceIn(pos, dur)
        timeTv.text = fmt(pos) + " / " + fmt(dur)
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        btnPlay.setImageResource(if (vv.isPlaying) R.drawable.ic_pause_w else R.drawable.ic_play_w)
    }

    private fun updateVolIcon() {
        btnVolume.setImageResource(if (muted) R.drawable.ic_volume_off_w else R.drawable.ic_volume_on_w)
    }

    private fun applyVolume(mp: MediaPlayer) {
        val v = if (muted) 0f else 1f
        mp.setVolume(v, v)
    }

    private fun applySpeed(mp: MediaPlayer) {
        try {
            val was = vv.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
            if (!was) vv.pause()
        } catch (_: Exception) {
            toast("Speed not supported for this video")
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ------------------------------------------------------------------ menu

    private fun speedLabel() = if (speed == 1f) "1x" else "${speed}x"

    private fun showMenu() {
        val pm = PopupMenu(this, tb, Gravity.END)
        pm.setForceShowIcon(true)
        val m = pm.menu
        m.add(0, 1, 0, "About").setIcon(R.drawable.ic_info)
        m.add(0, 2, 1, if (Prefs.loop(this)) "Loop video on" else "Loop video off").setIcon(R.drawable.ic_repeat)
        m.add(0, 3, 2, "Playback speed (${speedLabel()})").setIcon(R.drawable.ic_speed)
        if (local) m.add(0, 4, 3, "Back up now").setIcon(R.drawable.ic_cloud_upload)
        m.add(0, 5, 4, "Move to folder").setIcon(R.drawable.ic_move)
        m.add(0, 6, 5, "Copy to folder").setIcon(R.drawable.ic_copy)
        m.add(0, 7, 6, if (slideshow) "Stop slideshow" else "Slideshow").setIcon(R.drawable.ic_slideshow)
        m.add(0, 8, 7, "Cast").setIcon(R.drawable.ic_cast)
        m.add(0, 9, 8, "Delete file").setIcon(R.drawable.ic_delete)
        pm.setOnMenuItemClickListener { onMenu(it.itemId); true }
        pm.show()
    }

    private fun onMenu(id: Int) {
        val name = videos[index]
        when (id) {
            1 -> about()
            2 -> {
                val v = !Prefs.loop(this)
                Prefs.setLoop(this, v)                      // global setting
                player?.isLooping = v && !slideshow
                toast(if (v) "Loop video on" else "Loop video off")
            }
            3 -> speedDialog()
            4 -> MediaActions.backup(this, curLoc(), name) { cb -> pending = cb; picker.launch(Unit) }
            5 -> { pending = { d -> MediaActions.copyMove(this, curLoc(), name, d, true) { finish() } }; picker.launch(Unit) }
            6 -> { pending = { d -> MediaActions.copyMove(this, curLoc(), name, d, false) { } }; picker.launch(Unit) }
            7 -> {
                slideshow = !slideshow
                player?.isLooping = Prefs.loop(this) && !slideshow
                toast(if (slideshow) "Slideshow: the next video plays when this one ends" else "Slideshow off")
            }
            8 -> MediaActions.cast(this)
            9 -> MediaActions.delete(this, curLoc(), name) { finish() }
        }
    }

    private fun about() {
        val mp = player
        val extra = if (mp == null) null
        else "Duration: ${fmt(vv.duration)}\nResolution: ${mp.videoWidth} x ${mp.videoHeight}"
        MediaActions.about(this, curLoc(), videos[index], extra)
    }

    private fun speedDialog() {
        val labels = speeds.map { if (it == 1f) "1x (normal)" else "${it}x" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == speed }.coerceAtLeast(0)) { d, i ->
                speed = speeds[i]
                player?.let { applySpeed(it) }
                d.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ save current frame as a photo

    private fun savePhoto() {
        val mp = player ?: return
        val vw = mp.videoWidth
        val vh = mp.videoHeight
        val w = vv.width
        val h = vv.height
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val fname = videos[index].substringBeforeLast('.') + "_" + (vv.currentPosition / 1000) + "s.jpg"
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(vv, full, { res ->
            if (res != PixelCopy.SUCCESS) {
                toast("Cannot capture this frame")
            } else {
                val s = minOf(w.toFloat() / vw, h.toFloat() / vh)
                val rw = (vw * s).toInt().coerceIn(1, w)
                val rh = (vh * s).toInt().coerceIn(1, h)
                saveBitmap(Bitmap.createBitmap(full, (w - rw) / 2, (h - rh) / 2, rw, rh), fname)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveBitmap(b: Bitmap, fname: String) {
        Thread {
            val ok = try {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fname)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RcloneManager")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread { toast(if (ok) "Saved to Pictures/RcloneManager" else "Cannot save photo") }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        if (::vv.isInitialized) { vv.pause(); updatePlayIcon() }
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(autoHide)
        if (::vv.isInitialized) vv.stopPlayback()
        if (acquired) { RcServer.pins--; RcServer.release() }
        super.onDestroy()
    }
}
