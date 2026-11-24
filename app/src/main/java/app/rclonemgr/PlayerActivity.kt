package app.rclonemgr

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Video player on Media3 / ExoPlayer with Google-Photos-like controls.
 * Tap = show / hide bars, double tap right / left = +10 s / -10 s, hold right / left = 2x speed.
 */
class PlayerActivity : BaseActivity() {
    private lateinit var root: View
    private lateinit var pv: PlayerView
    private lateinit var touch: View
    private lateinit var progress: View
    private lateinit var bar: View
    private lateinit var controls: View
    private lateinit var tb: MaterialToolbar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnVolume: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeTv: TextView
    private lateinit var skipLeft: TextView
    private lateinit var skipRight: TextView
    private lateinit var holdTv: TextView
    private lateinit var player: ExoPlayer

    private var local = false
    private var remote = ""
    private var dir = ""
    private var videos: List<String> = emptyList()
    private var index = 0
    private var speed = 1f
    private var muted = false
    private var slideshow = false
    private var dragging = false
    private var holding = false
    private var barsShown = true
    private var lastSkip = 0L
    private var skipDir = 0
    private var skipAcc = 0
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
    private val autoHide = Runnable { if (player.isPlaying) setBars(false) }
    private val hideSkip = Runnable {
        skipLeft.animate().alpha(0f).setDuration(200).start()
        skipRight.animate().alpha(0f).setDuration(200).start()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.root); bar = findViewById(R.id.bar); controls = findViewById(R.id.controls)
        applyOverlayInsets(root, bar, controls)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        remote = intent.getStringExtra("remote") ?: run { finish(); return }
        dir = intent.getStringExtra("dir") ?: ""
        val name = intent.getStringExtra("name") ?: run { finish(); return }
        local = remote.isEmpty()
        videos = MediaHolder.videos.takeIf { name in it } ?: listOf(name)
        index = videos.indexOf(name)

        pv = findViewById(R.id.pv); touch = findViewById(R.id.touch); progress = findViewById(R.id.progress)
        btnPlay = findViewById(R.id.btnPlay); btnVolume = findViewById(R.id.btnVolume)
        seek = findViewById(R.id.seek); timeTv = findViewById(R.id.time)
        skipLeft = findViewById(R.id.skipLeft); skipRight = findViewById(R.id.skipRight)
        holdTv = findViewById(R.id.holdTv)

        player = buildPlayer()
        pv.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> progress.visibility = View.VISIBLE
                    Player.STATE_READY -> progress.visibility = View.GONE
                    Player.STATE_ENDED -> {
                        progress.visibility = View.GONE
                        if (slideshow && index + 1 < videos.size) play(index + 1) else setBars(true)
                    }
                    else -> { }
                }
                updatePlayIcon()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayIcon()
                if (isPlaying) setBars(barsShown) // (re)start the auto-hide timer
            }

            override fun onPlayerError(error: PlaybackException) {
                progress.visibility = View.GONE
                toast("Cannot play this video (${error.errorCodeName})")
            }
        })

        tb = findViewById(R.id.toolbar)
        tb.setTitleTextColor(Color.WHITE)
        tb.setNavigationIconTint(Color.WHITE)
        tb.setNavigationOnClickListener { finish() }
        tb.inflateMenu(R.menu.viewer)
        tb.menu.findItem(R.id.action_more)?.icon?.mutate()?.setTint(Color.WHITE)
        tb.setOnMenuItemClickListener { showMenu(); true }

        btnPlay.setOnClickListener { togglePlay(); setBars(true) }
        btnVolume.setOnClickListener { muted = !muted; applyVolume(); updateVolIcon() }
        findViewById<View>(R.id.btnSavePhoto).setOnClickListener { savePhoto() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) timeTv.text = fmt(p.toLong()) + " / " + fmt(player.duration)
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true; ui.removeCallbacks(autoHide) }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                player.seekTo(s.progress.toLong())
                setBars(true)
            }
        })
        setupGestures()
        updateVolIcon()
        play(index)
        ui.post(tick)
    }

    private fun buildPlayer(): ExoPlayer {
        // shared OkHttp client: HTTP/2, adds the Bearer token itself
        val http = OkHttpDataSource.Factory(Api.client)
        // up to 50 s ahead, but never more than 32 MB of RAM
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true
            )
            .build()
            .also { it.setSeekParameters(SeekParameters.CLOSEST_SYNC) }
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
            start(MediaItem.fromUri(Uri.fromFile(File(dir, name))))
        } else {
            start(MediaItem.fromUri(Api.streamUrl(remote, curLoc().child(name).path)))
        }
    }

    private fun start(item: MediaItem) {
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        player.setPlaybackSpeed(speed)
        applyVolume()
        applyLoop()
    }

    // ------------------------------------------------------------------ gestures

    /** -1 = left part of the screen, 1 = right part, 0 = middle */
    private fun zone(x: Float): Int {
        val w = touch.width.toFloat()
        return if (x < w * 0.35f) -1 else if (x > w * 0.65f) 1 else 0
    }

    private fun setupGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val z = zone(e.x)
                // right after a double tap, more single taps keep skipping (like YouTube)
                if (z != 0 && SystemClock.uptimeMillis() - lastSkip < 700) skip(z) else setBars(!barsShown)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val z = zone(e.x)
                if (z == 0) togglePlay() else skip(z)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (zone(e.x) != 0) startHold()
            }
        })
        touch.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) endHold()
            true
        }
    }

    private fun skip(dir: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSkip > 800 || dir != skipDir) skipAcc = 0
        skipDir = dir
        lastSkip = now
        skipAcc += 10
        val dur = player.duration
        val max = if (dur == C.TIME_UNSET || dur <= 0) Long.MAX_VALUE else dur
        player.seekTo((player.currentPosition + dir * 10_000L).coerceIn(0L, max))
        val shown = if (dir < 0) skipLeft else skipRight
        val other = if (dir < 0) skipRight else skipLeft
        other.animate().cancel(); other.alpha = 0f
        shown.text = (if (dir < 0) "-" else "+") + skipAcc + "s"
        shown.animate().cancel()
        shown.alpha = 1f
        ui.removeCallbacks(hideSkip)
        ui.postDelayed(hideSkip, 700)
    }

    private fun startHold() {
        holding = true
        player.setPlaybackSpeed(2f)
        holdTv.visibility = View.VISIBLE
        touch.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun endHold() {
        if (!holding) return
        holding = false
        player.setPlaybackSpeed(speed)
        holdTv.visibility = View.GONE
    }

    private fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = !(player.playWhenReady && player.playbackState != Player.STATE_ENDED)
        updatePlayIcon()
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
        if (!::player.isInitialized || dragging) return
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        if (seek.max != dur.toInt()) seek.max = dur.toInt()
        val pos = player.currentPosition.coerceIn(0L, dur)
        seek.progress = pos.toInt()
        seek.secondaryProgress = player.bufferedPosition.coerceIn(pos, dur).toInt()   // exact buffered position
        timeTv.text = fmt(pos) + " / " + fmt(dur)
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause_w else R.drawable.ic_play_w)
    }

    private fun updateVolIcon() {
        btnVolume.setImageResource(if (muted) R.drawable.ic_volume_off_w else R.drawable.ic_volume_on_w)
    }

    private fun applyVolume() { player.volume = if (muted) 0f else 1f }

    private fun applyLoop() {
        player.repeatMode = if (Prefs.loop(this) && !slideshow) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun fmt(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
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
                applyLoop()
                toast(if (v) "Loop video on" else "Loop video off")
            }
            3 -> speedDialog()
            4 -> MediaActions.backup(this, curLoc(), name) { cb -> pending = cb; picker.launch(Unit) }
            5 -> { pending = { d -> MediaActions.copyMove(this, curLoc(), name, d, true) { finish() } }; picker.launch(Unit) }
            6 -> { pending = { d -> MediaActions.copyMove(this, curLoc(), name, d, false) { } }; picker.launch(Unit) }
            7 -> {
                slideshow = !slideshow
                applyLoop()
                toast(if (slideshow) "Slideshow: the next video plays when this one ends" else "Slideshow off")
            }
            8 -> MediaActions.cast(this)
            9 -> MediaActions.delete(this, curLoc(), name) { finish() }
        }
    }

    private fun about() {
        val vs = player.videoSize
        val extra = "Duration: ${fmt(player.duration)}\nResolution: ${vs.width} x ${vs.height}"
        MediaActions.about(this, curLoc(), videos[index], extra)
    }

    private fun speedDialog() {
        val labels = speeds.map { if (it == 1f) "1x (normal)" else "${it}x" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == speed }.coerceAtLeast(0)) { d, i ->
                speed = speeds[i]
                player.setPlaybackSpeed(speed)
                d.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ save current frame as a photo

    private fun savePhoto() {
        val sv = pv.videoSurfaceView as? SurfaceView ?: return
        val w = sv.width
        val h = sv.height
        if (w <= 0 || h <= 0) return
        val fname = videos[index].substringBeforeLast('.') + "_" + (player.currentPosition / 1000) + "s.jpg"
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sv, bmp, { res ->
            if (res == PixelCopy.SUCCESS) saveBitmap(bmp, fname) else toast("Cannot capture this frame")
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
        if (::player.isInitialized) { endHold(); player.pause(); updatePlayIcon() }
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(autoHide)
        ui.removeCallbacks(hideSkip)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
