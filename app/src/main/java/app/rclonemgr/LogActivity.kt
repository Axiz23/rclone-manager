package app.rclonemgr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar

/** Client log viewer (newest at the bottom). */
class LogActivity : BaseActivity() {
    private lateinit var tv: TextView
    private lateinit var scroll: NestedScrollView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_page)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.title = "Client logs"
        tb.setNavigationOnClickListener { finish() }
        tb.menu.add(0, 1, 0, "Refresh").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        tb.menu.add(0, 2, 1, "Copy").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        tb.menu.add(0, 3, 2, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        tb.menu.add(0, 4, 3, "Clear").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        tb.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> load()
                2 -> {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", ClientLog.read()))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                3 -> startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ClientLog.read().takeLast(200_000)), "Share log"))
                4 -> { ClientLog.clear(); load() }
            }
            true
        }
        scroll = findViewById(R.id.scroll)
        tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        findViewById<LinearLayout>(R.id.content).addView(tv)
        load()
    }

    private fun load() {
        val t = ClientLog.read()
        tv.text = if (t.isEmpty()) "No log entries yet." else t
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
