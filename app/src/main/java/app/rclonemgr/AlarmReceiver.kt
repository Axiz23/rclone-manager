package app.rclonemgr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives job alarms and system events (boot, package update, time / time-zone change) that require rescheduling. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // Boot, update, clock change or alarm permission change: reschedule everything
        if (i.action != Scheduler.ACTION_RUN) {
            Scheduler.scheduleAll(ctx)
            return
        }
        val job = JobStore.get(ctx, i.getStringExtra("id") ?: return) ?: return
        if (!job.enabled) return

        Scheduler.schedule(ctx, job) // schedule the next trigger first, so a crash cannot stop the series

        val why = Conditions.unmet(ctx, job)
        if (why != null) {
            job.lastStatus = "Deferred: $why"
            JobStore.save(ctx, job)
            val retryAt = System.currentTimeMillis() + Scheduler.RETRY_MS
            if (Scheduler.nextTrigger(job) > retryAt) Scheduler.setAt(ctx, job.id, retryAt)
            return
        }
        try {
            ctx.startForegroundService(BackupService.intent(ctx, job.id))
        } catch (e: Exception) {
            job.lastStatus = "Failed to start service (${e.javaClass.simpleName}). Grant the exact alarm permission."
            JobStore.save(ctx, job)
        }
    }
}
