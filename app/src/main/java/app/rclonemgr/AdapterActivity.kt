package app.rclonemgr

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** Edits the connection adapter (JSON). Saved changes apply immediately: no new APK needed. */
class AdapterActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_page)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "Connection adapter"
            setNavigationOnClickListener { finish() }
        }
        val content = findViewById<LinearLayout>(R.id.content)
        content.setPadding(dp(16), dp(8), dp(16), 0)

        content.addView(TextView(this).apply {
            text = "Describes how the app talks to the API: URL prefix, auth, endpoints, parameters and response fields. " +
                "Placeholders: {remote} {path} {name} {dest} {destRemote} {isDir} {modtime} {id}. " +
                "Auth types: bearer, header (name, prefix), query (name)."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        val et = EditText(this).apply {
            setText(Api.adapterJson())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.TOP
            minLines = 16
            setHorizontallyScrolling(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        content.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val reset = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Reset to default" }
        val save = MaterialButton(this).apply { text = "Save" }
        row.addView(reset)
        row.addView(save)
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        save.setOnClickListener {
            try {
                Api.setAdapter(this, et.text.toString())
                ClientLog.i("Settings", "Adapter saved (${Api.adapterName()})")
                Toast.makeText(this, "Adapter saved", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                et.error = e.message
            }
        }
        reset.setOnClickListener {
            Api.setAdapter(this, null)
            ClientLog.i("Settings", "Adapter reset to default")
            et.setText(Api.adapterJson())
            Toast.makeText(this, "Default adapter restored", Toast.LENGTH_SHORT).show()
        }
    }
}
