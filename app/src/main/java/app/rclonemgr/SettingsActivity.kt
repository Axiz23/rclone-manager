package app.rclonemgr

import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Settings page: server, connection adapter, playback, cache & memory, logs. */
class SettingsActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var serverStatus: TextView
    private lateinit var adapterSummary: TextView
    private lateinit var memSummary: TextView
    private lateinit var cacheSummary: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_page)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "Settings"
            setNavigationOnClickListener { finish() }
        }
        content = findViewById(R.id.content)
        buildServer()
        buildAdapter()
        buildLock()
        buildPlayback()
        buildCache()
        buildLogs()
    }

    override fun onResume() {
        super.onResume()
        adapterSummary.text = "${Api.adapterName()} · tap to edit"
        refreshInfo()
    }

    // ---- helpers
    private fun header(t: String) {
        content.addView(TextView(this).apply {
            text = t
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(16), dp(24), dp(16), dp(4))
        })
    }

    private fun row(title: String, summary: String?, click: (() -> Unit)?): TextView {
        val v = layoutInflater.inflate(R.layout.row_setting, content, false)
        v.findViewById<TextView>(R.id.title).text = title
        val s = v.findViewById<TextView>(R.id.summary)
        if (summary == null) s.visibility = View.GONE else s.text = summary
        if (click != null) v.setOnClickListener { click() } else { v.isClickable = false; v.background = null }
        content.addView(v)
        return s
    }

    private fun field(box: LinearLayout, hint: String, initial: String, type: Int, password: Boolean): TextInputEditText {
        val til = TextInputLayout(this).apply {
            this.hint = hint
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val et = TextInputEditText(til.context).apply { setText(initial); inputType = type; setSingleLine() }
        til.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return et
    }

    // ---- sections
    private fun buildServer() {
        header("Server")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), 0) }
        val url = field(box, "Server URL (https://…)", Prefs.serverUrl(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, false)
        val tok = field(box, "Token (64 hex characters)", Prefs.token(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, true)
        val btn = MaterialButton(this).apply { text = "Connect" }
        serverStatus = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, dp(8), 0, 0)
        }
        fun status() {
            serverStatus.text = if (Api.configured()) "Connected · ${Prefs.remotes(this).size} remote(s)" else "Not connected"
        }
        status()
        btn.setOnClickListener {
            val u = url.text.toString().trim()
            val t = tok.text.toString().trim()
            if (!u.startsWith("https://") && !u.startsWith("http://")) { url.error = "Must start with https://"; return@setOnClickListener }
            if (t.isEmpty()) { tok.error = "Required"; return@setOnClickListener }
            btn.isEnabled = false; btn.text = "Connecting…"
            Thread {
                val err = try { Api.tryConfigure(this, u, t); null } catch (e: Exception) { e.message ?: "Connection failed" }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    btn.isEnabled = true; btn.text = "Connect"
                    if (err == null) { ClientLog.i("Settings", "Connected to $u"); status(); Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show() }
                    else { ClientLog.w("Settings", "Connect to $u failed: $err"); Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        box.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        box.addView(serverStatus)
        content.addView(box)
    }

    private fun buildAdapter() {
        header("Connection")
        adapterSummary = row("Connection adapter", "", { startActivity(Intent(this, AdapterActivity::class.java)) })
    }

    private fun buildLock() {
        header("Remote lock")
        val options = listOf(0 to "On app restart", 5 to "After 5 min idle", 15 to "After 15 min idle", 30 to "After 30 min idle", 60 to "After 1 hour idle")
        fun label() = options.firstOrNull { it.first == Prefs.lockMinutes(this) }?.second ?: options[0].second
        lateinit var summary: TextView
        summary = row("Remote session", label(), {
            val cur = options.indexOfFirst { it.first == Prefs.lockMinutes(this) }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("Keep unlocked remotes open")
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), cur) { d, i ->
                    Prefs.setLockMinutes(this, options[i].first)
                    summary.text = label()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        row("Lock all remotes now", "Uploads and downloads that are running are not interrupted", {
            Thread {
                val err = try { Api.lockAll(); null } catch (e: Exception) { e.message ?: "Failed" }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (err == null) Remotes.clearOpen()
                    Toast.makeText(this, err ?: "Remotes locked", Toast.LENGTH_SHORT).show()
                }
            }.start()
        })
    }

    private fun buildPlayback() {
        header("Playback")
        val v = layoutInflater.inflate(R.layout.row_switch, content, false)
        v.findViewById<TextView>(R.id.title).text = "Loop videos"
        v.findViewById<TextView>(R.id.summary).text = "Restart a video automatically when it ends"
        val sw = v.findViewById<MaterialSwitch>(R.id.sw)
        sw.isChecked = Prefs.loop(this)
        sw.setOnCheckedChangeListener { _, c -> Prefs.setLoop(this, c) }
        v.setOnClickListener { sw.toggle() }
        content.addView(v)
    }

    private fun buildCache() {
        header("Cache & memory")
        memSummary = row("Memory now", "", { refreshInfo() })
        cacheSummary = row("Disk cache", "", null)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(8)) }
        box.addView(TextView(this).apply {
            text = "Limit for folders, photos and video (video cache applies after a restart)"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        val group = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val sizes = listOf(100, 300, 1000)
        val ids = HashMap<Int, Int>()
        for (mb in sizes) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = "$mb MB"
            }
            ids[b.id] = mb
            group.addView(b)
        }
        ids.entries.firstOrNull { it.value == Prefs.cacheMb(this) }?.let { group.check(it.key) }
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { Prefs.setCacheMb(this, ids[id] ?: 300); refreshInfo() }
        }
        box.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        content.addView(box)

        row("Clear all caches", "Thumbnails, folder listings, photos, video", {
            Thumbs.clear(this); DiskCache.clear(this)
            ClientLog.i("Settings", "Caches cleared")
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
            refreshInfo()
        })
    }

    private fun buildLogs() {
        header("Logs")
        row("Client logs", "Errors and events from this app", { startActivity(Intent(this, LogActivity::class.java)) })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        row("Version", ver, null)
    }

    private fun refreshInfo() {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val gfx = mi.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0
        memSummary.text = "${mi.totalPss / 1024} MB  (Java ${mi.dalvikPss / 1024} · native ${mi.nativePss / 1024} · graphics ${gfx / 1024})\nTap to refresh"
        Thread {
            val t = Thumbs.diskSize(this)
            val o = DiskCache.size(this)
            runOnUiThread {
                if (!isDestroyed) cacheSummary.text =
                    "Thumbnails ${Formatter.formatShortFileSize(this, t)} (limit 100 MB)\n" +
                        "Folders, photos, video ${Formatter.formatShortFileSize(this, o)} (limit ${Prefs.cacheMb(this)} MB)"
            }
        }.start()
    }
}
