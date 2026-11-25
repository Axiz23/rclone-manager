package app.rclonemgr

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Small shared dialogs. */
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
}
