package app.rclonemgr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var info: TextView
    private lateinit var permCard: View
    private lateinit var permText: TextView
    private lateinit var permBtn: Button

    private val pickConf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importConf) }
    private val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(b: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(b)
        edgeToEdge()
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        info = findViewById(R.id.info)
        permCard = findViewById(R.id.permCard)
        permText = findViewById(R.id.permText)
        permBtn = findViewById(R.id.permBtn)

        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.inflateMenu(R.menu.main)
        tb.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_import) { pickConf.launch(arrayOf("*/*")); true } else false
        }
        findViewById<View>(R.id.fab).setOnClickListener {
            startActivity(Intent(this, JobEditActivity::class.java))
        }
    }

    override fun onStart() { super.onStart(); Ui.visible = true; refresh() }
    override fun onStop() { super.onStop(); Ui.visible = false }

    private fun pkgUri() = Uri.parse("package:$packageName")

    private fun refresh() {
        // ---- izin yang masih kurang
        val need = ArrayList<Pair<String, () -> Unit>>()
        if (!Environment.isExternalStorageManager())
            need += "All files access — needed to read source folders." to {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri()))
            }
        if (!Scheduler.canExact(this))
            need += "Exact alarms — so schedules run on time in the background." to {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri()))
            }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += "Notifications — for backup progress and results." to { reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            need += "Ignore battery optimization (recommended)." to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri()))
            }
        if (need.isEmpty()) permCard.visibility = View.GONE else {
            permCard.visibility = View.VISIBLE
            permText.text = "Permission needed (${need.size}): " + need[0].first
            permBtn.setOnClickListener { need[0].second() }
        }

        // ---- info remote
        val remotes = RcloneRunner.remotes(this)
        info.text = if (remotes.isEmpty()) "No rclone.conf yet. Import it from the ⋮ menu."
        else "Remotes: " + remotes.joinToString(", ")

        // ---- daftar job
        list.removeAllViews()
        val jobs = JobStore.all(this)
        empty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (j in jobs) {
            val v = layoutInflater.inflate(R.layout.item_job, list, false)
            v.findViewById<TextView>(R.id.name).text = j.name
            v.findViewById<TextView>(R.id.route).text =
                "${j.source} → ${j.dest()} (${if (j.sync) "sync" else "copy"})"
            v.findViewById<TextView>(R.id.trigger).text = j.triggerText()
            v.findViewById<TextView>(R.id.status).text =
                if (j.lastRun == 0L && j.lastStatus.isEmpty()) "Never run"
                else (if (j.lastRun > 0) df.format(Date(j.lastRun)) + " — " else "") + j.lastStatus
            val sw = v.findViewById<MaterialSwitch>(R.id.sw)
            sw.isChecked = j.enabled
            sw.setOnCheckedChangeListener { _, on ->
                j.enabled = on
                JobStore.save(this, j)
                Scheduler.schedule(this, j)
            }
            v.setOnClickListener {
                startActivity(Intent(this, JobEditActivity::class.java).putExtra("id", j.id))
            }
            v.findViewById<Button>(R.id.btnRun).setOnClickListener {
                if (RcloneRunner.remotes(this).isEmpty()) toast("Import rclone.conf first")
                else {
                    startForegroundService(BackupService.intent(this, j.id))
                    toast("Backup started")
                }
            }
            v.findViewById<Button>(R.id.btnLog).setOnClickListener { showLog(j) }
            list.addView(v)
        }
    }

    private fun showLog(j: Job) {
        val lines = JobLog.read(this, j.id)
        val text = if (lines.isEmpty()) "No log yet." else lines.joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle("${j.name} (last ${JobLog.SHOW_LINES} lines)")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear log") { _, _ -> JobLog.clear(this, j.id) }
            .show()
    }

    private fun importConf(uri: Uri) {
        try {
            val dst = RcloneRunner.confFile(this)
            val tmp = File(dst.path + ".tmp")
            contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(dst)) throw java.io.IOException("rename gagal")
            toast("${RcloneRunner.remotes(this).size} remote(s) found")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
