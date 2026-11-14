package app.rclonemgr

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import java.util.Calendar

object Scheduler {
    const val ACTION_RUN = "app.rclonemgr.RUN"
    const val RETRY_MS = 30 * 60_000L

    private fun pi(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_RUN)
            .setData(Uri.parse("rcm://job/$id"))
            .putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun nextTrigger(job: Job, now: Long = System.currentTimeMillis()): Long {
        if (job.trigger == TRIGGER_INTERVAL) return now + job.intervalMin * 60_000L
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, job.hour); set(Calendar.MINUTE, job.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            val idx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Senin = 0 ... Minggu = 6
            if (cal.timeInMillis > now && (job.days and (1 shl idx)) != 0) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return now + 24 * 3600_000L
    }

    fun schedule(ctx: Context, job: Job) {
        if (job.enabled) setAt(ctx, job.id, nextTrigger(job)) else cancel(ctx, job.id)
    }

    fun setAt(ctx: Context, id: String, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, id))
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id))
    }

    fun scheduleAll(ctx: Context) = JobStore.all(ctx).forEach { schedule(ctx, it) }
}

object Conditions {
    /** null = semua syarat terpenuhi, selain itu alasan penundaan. */
    fun unmet(ctx: Context, job: Job): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "tidak ada jaringan"
        if (job.wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            return "bukan Wi-Fi/jaringan unmetered"
        if (job.chargingOnly) {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if ((b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) == 0) return "tidak sedang mengisi daya"
        }
        return null
    }
}
