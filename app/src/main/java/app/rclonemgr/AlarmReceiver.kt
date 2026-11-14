package app.rclonemgr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // Boot / update / ganti waktu / izin alarm berubah -> jadwalkan ulang semua
        if (i.action != Scheduler.ACTION_RUN) {
            Scheduler.scheduleAll(ctx)
            return
        }
        val job = JobStore.get(ctx, i.getStringExtra("id") ?: return) ?: return
        if (!job.enabled) return

        Scheduler.schedule(ctx, job) // jadwalkan pemicu berikutnya lebih dulu

        val why = Conditions.unmet(ctx, job)
        if (why != null) {
            job.lastStatus = "Ditunda: $why"
            JobStore.save(ctx, job)
            val retryAt = System.currentTimeMillis() + Scheduler.RETRY_MS
            if (Scheduler.nextTrigger(job) > retryAt) Scheduler.setAt(ctx, job.id, retryAt)
            return
        }
        try {
            ctx.startForegroundService(BackupService.intent(ctx, job.id))
        } catch (e: Exception) {
            job.lastStatus = "Gagal start service (${e.javaClass.simpleName}). Beri izin alarm tepat waktu."
            JobStore.save(ctx, job)
        }
    }
}
