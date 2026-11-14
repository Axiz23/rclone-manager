package app.rclonemgr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BackupService : Service() {
    private val pending = AtomicInteger(0)
    private var exec: ExecutorService? = null
    private var wl: PowerManager.WakeLock? = null
    @Volatile private var proc: Process? = null
    @Volatile private var stopped = false
    @Volatile private var lastStartId = 0
    private var lastNotify = 0L

    companion object {
        const val CH_RUN = "run"
        const val CH_DONE = "done"
        const val EXTRA_ID = "id"
        const val ACTION_STOP = "app.rclonemgr.STOP"
        private const val NID = 1

        fun intent(ctx: Context, id: String) =
            Intent(ctx, BackupService::class.java).putExtra(EXTRA_ID, id)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Backup running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Backup results", NotificationManager.IMPORTANCE_DEFAULT))
        wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rclonemgr:backup")
            .apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        exec = Executors.newSingleThreadExecutor() // job dijalankan berurutan -> memori tetap rendah
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForeground(NID, buildRun("Rclone Manager", "Preparing…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (i?.action == ACTION_STOP) {
            stopped = true
            proc?.destroy()
            if (pending.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        val id = i?.getStringExtra(EXTRA_ID)
        if (id == null) { if (pending.get() == 0) stopSelf(startId); return START_NOT_STICKY }

        stopped = false
        pending.incrementAndGet()
        exec?.execute {
            try {
                runJob(id)
            } catch (_: Throwable) {
            } finally {
                if (pending.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15: batas waktu FGS dataSync
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopped = true
        proc?.destroy()
        stopSelf()
    }

    private fun runJob(id: String) {
        if (stopped) return
        val job = JobStore.get(this, id) ?: return
        notifyRun(job.name, "Starting…", true)
        val code = RcloneRunner.run(this, job, { proc = it }) { notifyRun(job.name, it, false) }
        job.lastRun = System.currentTimeMillis()
        job.lastStatus = when {
            stopped -> "Cancelled"
            code == 0 -> "Success"
            else -> "Failed (code $code)"
        }
        JobStore.save(this, job)
        getSystemService(NotificationManager::class.java).notify(
            id.hashCode(),
            Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_cloud_upload)
                .setContentTitle(job.name).setContentText(job.lastStatus)
                .setContentIntent(openApp()).setAutoCancel(true).build()
        )
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildRun(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BackupService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Cancel", stop).build())
            .build()
    }

    private fun notifyRun(title: String, text: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 2000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(NID, buildRun(title, text))
    }

    override fun onDestroy() {
        exec?.shutdownNow()
        proc?.destroy()
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
        // Tidak ada UI -> matikan proses agar RAM idle = 0 (alarm akan membangunkan lagi)
        if (!Ui.visible) android.os.Process.killProcess(android.os.Process.myPid())
    }
}
