package app.rclonemgr

import android.app.Activity
import android.text.format.Formatter
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a file operation on a worker thread with a progress dialog (Cancel stops the rclone job). */
object OpRunner {
    fun run(
        a: Activity,
        title: String,
        rc: Boolean,
        work: (AtomicBoolean, (Long, Long) -> Unit) -> Unit,
        done: (String?) -> Unit
    ) {
        val cancel = AtomicBoolean(false)
        val tv = TextView(a).apply {
            text = "Working…"
            setPadding(a.dp(24), a.dp(16), a.dp(24), a.dp(8))
        }
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(tv)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }
            .show()
        RcServer.pins++
        if (rc) RcServer.acquire(a)
        Thread {
            var err: String? = null
            try {
                work(cancel) { b, t ->
                    a.runOnUiThread {
                        tv.text = Formatter.formatShortFileSize(a, b) + " / " + Formatter.formatShortFileSize(a, t)
                    }
                }
            } catch (e: Ops.Cancelled) {
                err = "Cancelled"
            } catch (e: Exception) {
                err = e.message ?: "Error"
            }
            a.runOnUiThread {
                RcServer.pins--
                if (rc) RcServer.release()
                if (!a.isDestroyed) {
                    if (dlg.isShowing) dlg.dismiss()
                    done(err)
                }
            }
        }.start()
    }
}
