package app.rclonemgr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object Dialogs {
    fun prompt(a: Activity, title: String, hint: String, initial: String, ok: String, onOk: (String) -> Unit) {
        val v = a.layoutInflater.inflate(R.layout.dialog_input, null)
        val til = v.findViewById<TextInputLayout>(R.id.til)
        val et = v.findViewById<TextInputEditText>(R.id.input)
        til.hint = hint
        et.setText(initial)
        et.setSelection(0, initial.substringBeforeLast('.', initial).length)
        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(v)
            .setPositiveButton(ok) { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty() || t.contains('/')) Toast.makeText(a, "Invalid name", Toast.LENGTH_SHORT).show()
                else onOk(t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun thumbLog(a: Activity) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Thumbnail log")
            .setMessage(ThumbLog.text())
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                a.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("thumbnail log", ThumbLog.text()))
                Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Clear") { _, _ -> ThumbLog.clear() }
            .show()
    }

    fun settings(a: Activity) {
        val labels = arrayOf("Use .thumbnails folder when available", "Generate thumbnails from source", "Loop videos")
        val st = booleanArrayOf(Prefs.useThumbFolder(a), Prefs.generateThumbs(a), Prefs.loop(a))
        val mb = Thumbs.diskSize(a) / (1024 * 1024)
        MaterialAlertDialogBuilder(a)
            .setTitle("Settings")
            .setMultiChoiceItems(labels, st) { _, i, c -> st[i] = c }
            .setPositiveButton("Save") { _, _ ->
                Prefs.setUseThumbFolder(a, st[0])
                Prefs.setGenerateThumbs(a, st[1])
                Prefs.setLoop(a, st[2])
            }
            .setNeutralButton("Clear thumbnail cache ($mb MB)") { _, _ ->
                Thumbs.clear(a)
                Toast.makeText(a, "Thumbnail cache cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
