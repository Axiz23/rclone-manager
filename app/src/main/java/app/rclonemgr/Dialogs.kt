package app.rclonemgr

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
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

    /** Server URL + token. Saved only after the server accepts them (GET /server/settings). */
    fun server(a: Activity, onSaved: () -> Unit) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        }
        fun field(hint: String, initial: String, type: Int, password: Boolean): TextInputEditText {
            val til = TextInputLayout(a).apply {
                this.hint = hint
                if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            val et = TextInputEditText(til.context).apply {
                setText(initial); inputType = type; setSingleLine()
            }
            til.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return et
        }
        val url = field("Server URL (https://…)", Prefs.serverUrl(a), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, false)
        val tok = field("Token (64 hex characters)", Prefs.token(a),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, true)
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("Server")
            .setView(box)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            val btn = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val u = url.text.toString().trim()
                val t = tok.text.toString().trim()
                if (!u.startsWith("https://") && !u.startsWith("http://")) { url.error = "Must start with https://"; return@setOnClickListener }
                if (t.isEmpty()) { tok.error = "Required"; return@setOnClickListener }
                btn.isEnabled = false
                btn.text = "Connecting…"
                Thread {
                    val err = try { Api.tryConfigure(a, u, t); null } catch (e: Exception) { e.message ?: "Connection failed" }
                    a.runOnUiThread {
                        if (a.isDestroyed) return@runOnUiThread
                        if (err == null) { dlg.dismiss(); onSaved() }
                        else { btn.isEnabled = true; btn.text = "Connect"; Toast.makeText(a, err, Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
        }
        dlg.show()
    }

    fun settings(a: Activity) {
        val labels = arrayOf("Loop videos")
        val st = booleanArrayOf(Prefs.loop(a))
        val mb = Thumbs.diskSize(a) / (1024 * 1024)
        MaterialAlertDialogBuilder(a)
            .setTitle("Settings")
            .setMultiChoiceItems(labels, st) { _, i, c -> st[i] = c }
            .setPositiveButton("Save") { _, _ -> Prefs.setLoop(a, st[0]) }
            .setNeutralButton("Clear thumbnail cache ($mb MB)") { _, _ ->
                Thumbs.clear(a)
                Toast.makeText(a, "Thumbnail cache cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
