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
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root))
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
            need += "Akses semua file — diperlukan untuk membaca folder sumber." to {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri()))
            }
        if (!Scheduler.canExact(this))
            need += "Alarm tepat waktu — agar jadwal berjalan akurat di background." to {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri()))
            }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += "Notifikasi — untuk progres dan hasil backup." to { reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            need += "Abaikan optimasi baterai (disarankan)." to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri()))
            }
        if (need.isEmpty()) permCard.visibility = View.GONE else {
            permCard.visibility = View.VISIBLE
            permText.text = "Izin diperlukan (${need.size}): " + need[0].first
            permBtn.setOnClickListener { need[0].second() }
        }

        // ---- info remote
        val remotes = RcloneRunner.remotes(this)
        info.text = if (remotes.isEmpty()) "Belum ada rclone.conf. Impor lewat menu ⋮."
        else "Remote: " + remotes.joinToString(", ")

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
                if (j.lastRun == 0L && j.lastStatus.isEmpty()) "Belum pernah berjalan"
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
                if (RcloneRunner.remotes(this).isEmpty()) toast("Impor rclone.conf dulu")
                else {
                    startForegroundService(BackupService.intent(this, j.id))
                    toast("Backup dimulai")
                }
            }
            v.findViewById<Button>(R.id.btnLog).setOnClickListener { showLog(j) }
            list.addView(v)
        }
    }

    private fun showLog(j: Job) {
        val f = RcloneRunner.logFile(this, j.id)
        val text = if (f.exists()) f.readText() else "Belum ada log."
        MaterialAlertDialogBuilder(this).setTitle(j.name).setMessage(text).setPositiveButton("Tutup", null).show()
    }

    private fun importConf(uri: Uri) {
        try {
            val dst = RcloneRunner.confFile(this)
            val tmp = File(dst.path + ".tmp")
            contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(dst)) throw java.io.IOException("rename gagal")
            toast("${RcloneRunner.remotes(this).size} remote ditemukan")
        } catch (e: Exception) {
            toast("Gagal impor: ${e.message}")
        }
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
