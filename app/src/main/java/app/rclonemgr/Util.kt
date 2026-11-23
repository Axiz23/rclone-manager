package app.rclonemgr

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Counts started activities; used to decide whether the process may be killed after a backup finishes. */
object Ui {
    private val n = java.util.concurrent.atomic.AtomicInteger()
    val visible: Boolean get() = n.get() > 0
    fun started() { n.incrementAndGet() }
    fun stopped() { n.decrementAndGet() }
}

object Paths {
    /** Ubah URI hasil OpenDocumentTree menjadi path file biasa (rclone butuh path, bukan URI). */
    fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        val rel = parts.getOrElse(1) { "" }
        val base = if (parts[0] == "primary") "/storage/emulated/0" else "/storage/${parts[0]}"
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}

/** Edge-to-edge on every Android version (Android < 15 does not enforce it), transparent system bars. */
fun Activity.edgeToEdge() {
    // Without this, hiding the status bar makes the window shrink / move by the height of the notch.
    window.attributes = window.attributes.also {
        it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val c = WindowCompat.getInsetsController(window, window.decorView)
    c.isAppearanceLightStatusBars = !night
    c.isAppearanceLightNavigationBars = !night
}

/** The app bar draws under the status bar (same color); other insets pad the root. */
fun Activity.applyInsets(root: View, appBar: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        appBar.setPadding(0, b.top, 0, 0)
        v.setPadding(b.left, 0, b.right, b.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

/**
 * For full-screen viewers: the content (video / image) is NOT padded, so it never moves when the system bars
 * are hidden or shown. Only the overlays get padding, from the stable (always-visible) insets.
 */
fun Activity.applyOverlayInsets(root: View, top: View, bottom: View?) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val b = Insets.max(
            insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()),
            insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        )
        top.setPadding(b.left, b.top, b.right, 0)
        bottom?.setPadding(dp(16) + b.left, dp(24), dp(16) + b.right, dp(8) + b.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

fun android.content.Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
