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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : BaseActivity() {
    private lateinit var drawer: DrawerLayout
    private lateinit var nav: NavigationView
    private lateinit var tb: MaterialToolbar
    private lateinit var pageRemotes: View
    private lateinit var pageJobs: View
    private lateinit var remotesList: LinearLayout
    private lateinit var remotesEmpty: TextView
    private lateinit var fab: View
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var permCard: View
    private lateinit var permText: TextView
    private lateinit var permBtn: Button
    private var page = 0   // 0 = Remotes (default), 1 = Jobs

    private val pickConf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importConf) }
    private val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))
        drawer = findViewById(R.id.drawer); nav = findViewById(R.id.nav); tb = findViewById(R.id.toolbar)
        pageRemotes = findViewById(R.id.pageRemotes); pageJobs = findViewById(R.id.pageJobs)
        remotesList = findViewById(R.id.remotesList); remotesEmpty = findViewById(R.id.remotesEmpty)
        fab = findViewById(R.id.fab)
        list = findViewById(R.id.list); empty = findViewById(R.id.empty)
        permCard = findViewById(R.id.permCard); permText = findViewById(R.id.permText); permBtn = findViewById(R.id.permBtn)

        tb.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        tb.inflateMenu(R.menu.main)
        tb.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_import) { pickConf.launch(arrayOf("*/*")); true } else false
        }
        nav.setNavigationItemSelectedListener {
            drawer.closeDrawer(GravityCompat.START)
            when (it.itemId) {
                R.id.nav_remotes -> { showPage(0); true }
                R.id.nav_jobs -> { showPage(1); true }
                R.id.nav_settings -> { showSettings(); false }
                else -> false
            }
        }
        fab.setOnClickListener { startActivity(Intent(this, JobEditActivity::class.java)) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START)
                else if (page != 0) showPage(0)
                else finish()
            }
        })
        showPage(b?.getInt("page") ?: 0)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putInt("page", page) }

    override fun onStart() { super.onStart(); refresh() }

    private fun showPage(p: Int) {
        page = p
        pageRemotes.visibility = if (p == 0) View.VISIBLE else View.GONE
        pageJobs.visibility = if (p == 1) View.VISIBLE else View.GONE
        fab.visibility = if (p == 1) View.VISIBLE else View.GONE
        tb.title = if (p == 0) "Remotes" else "Jobs"
        nav.setCheckedItem(if (p == 0) R.id.nav_remotes else R.id.nav_jobs)
    }

    private fun pkgUri() = Uri.parse("package:$packageName")

    private fun refresh() {
        // ---- missing permissions
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

        // ---- remotes
        remotesList.removeAllViews()
        val remotes = RcloneRunner.remoteInfo(this)
        remotesEmpty.visibility = if (remotes.isEmpty()) View.VISIBLE else View.GONE
        for ((name, type) in remotes) {
            val v = layoutInflater.inflate(R.layout.item_remote, remotesList, false)
            v.findViewById<TextView>(R.id.name).text = name
            v.findViewById<TextView>(R.id.type).text = if (type.isEmpty()) "remote" else type
            v.setOnClickListener {
                startActivity(Intent(this, BrowseActivity::class.java).putExtra("remote", name))
            }
            remotesList.addView(v)
        }

        // ---- jobs
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

    private fun showSettings() {
        val labels = arrayOf("Use .thumbnails folder when available", "Generate thumbnails from source", "Loop videos")
        val st = booleanArrayOf(Prefs.useThumbFolder(this), Prefs.generateThumbs(this), Prefs.loop(this))
        val mb = Thumbs.diskSize(this) / (1024 * 1024)
        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(labels, st) { _, i, c -> st[i] = c }
            .setPositiveButton("Save") { _, _ ->
                Prefs.setUseThumbFolder(this, st[0])
                Prefs.setGenerateThumbs(this, st[1])
                Prefs.setLoop(this, st[2])
            }
            .setNeutralButton("Clear thumbnail cache ($mb MB)") { _, _ -> Thumbs.clear(this); toast("Thumbnail cache cleared") }
            .setNegativeButton("Cancel", null)
            .show()
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
            if (!tmp.renameTo(dst)) throw java.io.IOException("rename failed")
            toast("${RcloneRunner.remotes(this).size} remote(s) found")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
