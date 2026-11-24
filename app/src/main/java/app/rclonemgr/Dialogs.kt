package app.rclonemgr

import android.app.Activity
import android.os.Debug
import android.text.InputType
import android.text.format.Formatter
import android.graphics.Typeface
import android.widget.EditText
import android.widget.ScrollView
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
        MaterialAlertDialogBuilder(a)
            .setTitle("Settings")
            .setMultiChoiceItems(labels, st) { _, i, c -> st[i] = c }
            .setPositiveButton("Save") { _, _ -> Prefs.setLoop(a, st[0]) }
            .setNeutralButton("Cache & memory…") { _, _ -> cache(a) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Disk cache sizes + current memory use (to see where the RAM goes). */
    fun cache(a: Activity) {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        fun mb(kb: Int) = kb / 1024
        val gfx = mi.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0
        val limit = Prefs.cacheMb(a)
        val msg = "Memory now: ${mb(mi.totalPss)} MB\n" +
            "  Java ${mb(mi.dalvikPss)} · native ${mb(mi.nativePss)} · graphics ${mb(gfx)}\n\n" +
            "Thumbnails: ${Formatter.formatShortFileSize(a, Thumbs.diskSize(a))} (limit 100 MB)\n" +
            "Folders, photos, video: ${Formatter.formatShortFileSize(a, DiskCache.size(a))} (limit $limit MB)"
        val next = when (limit) { 100 -> 300; 300 -> 1000; else -> 100 }
        MaterialAlertDialogBuilder(a)
            .setTitle("Cache & memory")
            .setMessage(msg)
            .setPositiveButton("Clear all") { _, _ ->
                Thumbs.clear(a); DiskCache.clear(a)
                Toast.makeText(a, "Cache cleared", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Limit → $next MB") { _, _ ->
                Prefs.setCacheMb(a, next)
                Toast.makeText(a, "Limit $next MB (video cache applies after restart)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Edit the connection adapter (JSON). Takes effect immediately, no new APK needed. */
    fun adapter(a: Activity, onSaved: () -> Unit) {
        val et = EditText(a).apply {
            setText(Api.adapterJson())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setHorizontallyScrolling(true)
            gravity = android.view.Gravity.TOP
            minLines = 12
            setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        }
        val sv = ScrollView(a).apply { addView(et) }
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("Connection adapter")
            .setView(sv)
            .setPositiveButton("Save", null)
            .setNeutralButton("Reset default") { _, _ ->
                Api.setAdapter(a, null)
                Toast.makeText(a, "Default adapter restored", Toast.LENGTH_SHORT).show()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    Api.setAdapter(a, et.text.toString())
                    Toast.makeText(a, "Adapter saved", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    onSaved()
                } catch (e: Exception) {
                    et.error = e.message
                }
            }
        }
        dlg.show()
    }
}
