package app.rclonemgr

import android.app.Activity
import android.text.format.Formatter
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a file operation on a worker thread with a progress dialog (Cancel aborts the HTTP calls). */
object OpRunner {
    fun run(
        a: Activity,
        title: String,
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
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true); Api.cancel(Ops.TAG) }
            .show()
        Thread {
            var err: String? = null
            try {
                work(cancel) { b, t ->
                    a.runOnUiThread {
                        tv.text = if (t > 0) Formatter.formatShortFileSize(a, b) + " / " + Formatter.formatShortFileSize(a, t)
                        else Formatter.formatShortFileSize(a, b)
                    }
                }
            } catch (e: Ops.Cancelled) {
                err = "Cancelled"
            } catch (e: Exception) {
                err = if (cancel.get() || Api.isCanceled(e)) "Cancelled" else (e.message ?: "Error")
            }
            a.runOnUiThread {
                if (!a.isDestroyed) {
                    if (dlg.isShowing) dlg.dismiss()
                    done(err)
                }
            }
        }.start()
    }
}
