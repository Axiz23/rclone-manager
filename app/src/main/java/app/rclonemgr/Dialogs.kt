package app.rclonemgr

import android.app.Activity
import android.content.DialogInterface
import android.text.InputType
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Small shared dialogs. */
object Dialogs {
    /** Asks for the PIN of a locked remote. [onUnlocked] runs on the UI thread once the server accepted it. */
    fun pin(a: Activity, remote: String, onUnlocked: () -> Unit) {
        val v = a.layoutInflater.inflate(R.layout.dialog_input, null)
        val til = v.findViewById<TextInputLayout>(R.id.til)
        val et = v.findViewById<TextInputEditText>(R.id.input)
        til.hint = "PIN"
        til.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("Unlock $remote")
            .setView(v)
            .setPositiveButton("Unlock", null)   // set below, so a wrong PIN keeps the dialog open
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            val btn = dlg.getButton(DialogInterface.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val p = et.text.toString()
                if (p.isEmpty()) { til.error = "Enter the PIN"; return@setOnClickListener }
                btn.isEnabled = false
                til.error = null
                Thread {
                    val err = try { Api.unlock(a, remote, p); null } catch (e: Exception) { e.message ?: "Unlock failed" }
                    a.runOnUiThread {
                        if (a.isDestroyed) return@runOnUiThread
                        if (err == null) { dlg.dismiss(); onUnlocked() } else { btn.isEnabled = true; til.error = err }
                    }
                }.start()
            }
        }
        dlg.show()
    }

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
}
