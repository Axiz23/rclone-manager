package app.rclonemgr

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** true saat UI terlihat. Dipakai service untuk memutuskan apakah proses boleh dimatikan setelah backup selesai. */
object Ui {
    @Volatile var visible = false
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
