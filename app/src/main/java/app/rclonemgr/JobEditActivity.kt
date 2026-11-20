package app.rclonemgr

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.UUID

class JobEditActivity : BaseActivity() {
    private var editing: Job? = null
    private var hour = 2
    private var minute = 0

    private lateinit var nameEt: TextInputEditText
    private lateinit var srcEt: TextInputEditText
    private lateinit var srcBox: TextInputLayout
    private lateinit var remoteDd: MaterialAutoCompleteTextView
    private lateinit var pathEt: TextInputEditText
    private lateinit var excludeEt: TextInputEditText
    private lateinit var includeEt: TextInputEditText
    private lateinit var transfersEt: TextInputEditText
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var trigGroup: MaterialButtonToggleGroup
    private lateinit var intervalBox: View
    private lateinit var intervalEt: TextInputEditText
    private lateinit var timeBox: View
    private lateinit var timeBtn: Button
    private lateinit var daysGroup: ChipGroup
    private lateinit var wifiSw: MaterialSwitch
    private lateinit var chargeSw: MaterialSwitch

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val p = Paths.treeUriToPath(uri)
            if (p != null) srcEt.setText(p) else toast("Pick a folder on internal storage or an SD card")
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_job_edit)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))

        nameEt = findViewById(R.id.name); srcEt = findViewById(R.id.source); srcBox = findViewById(R.id.sourceBox)
        remoteDd = findViewById(R.id.remote); pathEt = findViewById(R.id.remotePath)
        modeGroup = findViewById(R.id.modeGroup); trigGroup = findViewById(R.id.trigGroup)
        intervalBox = findViewById(R.id.intervalBox); intervalEt = findViewById(R.id.interval)
        timeBox = findViewById(R.id.timeBox); timeBtn = findViewById(R.id.btnPickTime)
        daysGroup = findViewById(R.id.days); wifiSw = findViewById(R.id.wifi); chargeSw = findViewById(R.id.charge)
        excludeEt = findViewById(R.id.exclude); includeEt = findViewById(R.id.include)
        transfersEt = findViewById(R.id.transfers)

        srcBox.setEndIconOnClickListener { pickDir.launch(null) }
        remoteDd.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, RcloneRunner.remotes(this)))

        for (i in 0..6) {
            val c = layoutInflater.inflate(R.layout.chip_day, daysGroup, false) as Chip
            c.text = DAY_NAMES[i]; c.isChecked = true
            daysGroup.addView(c)
        }
        trigGroup.addOnButtonCheckedListener { _, id, checked -> if (checked) showTrigger(id == R.id.tInterval) }
        timeBtn.setOnClickListener {
            val p = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(hour).setMinute(minute).build()
            p.addOnPositiveButtonClickListener { hour = p.hour; minute = p.minute; updateTimeBtn() }
            p.show(supportFragmentManager, "t")
        }

        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.setNavigationOnClickListener { finish() }

        editing = intent.getStringExtra("id")?.let { JobStore.get(this, it) }
        editing?.let { j ->
            tb.title = "Edit job"
            tb.inflateMenu(R.menu.edit)
            tb.setOnMenuItemClickListener { confirmDelete(j); true }
            nameEt.setText(j.name); srcEt.setText(j.source); remoteDd.setText(j.remote, false); pathEt.setText(j.remotePath)
            modeGroup.check(if (j.sync) R.id.mSync else R.id.mCopy)
            trigGroup.check(if (j.trigger == TRIGGER_INTERVAL) R.id.tInterval else R.id.tTime)
            intervalEt.setText(j.intervalMin.toString())
            hour = j.hour; minute = j.minute
            for (i in 0..6) (daysGroup.getChildAt(i) as Chip).isChecked = (j.days and (1 shl i)) != 0
            wifiSw.isChecked = j.wifiOnly; chargeSw.isChecked = j.chargingOnly
            excludeEt.setText(j.exclude); includeEt.setText(j.include)
            transfersEt.setText(j.transfers.toString())
        } ?: run {
            tb.title = "New job"
            modeGroup.check(R.id.mCopy); trigGroup.check(R.id.tInterval)
            intervalEt.setText("60"); wifiSw.isChecked = true; transfersEt.setText("4")
        }
        updateTimeBtn()
        showTrigger(trigGroup.checkedButtonId == R.id.tInterval)
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun showTrigger(interval: Boolean) {
        intervalBox.visibility = if (interval) View.VISIBLE else View.GONE
        timeBox.visibility = if (interval) View.GONE else View.VISIBLE
    }

    private fun updateTimeBtn() { timeBtn.text = "At %02d:%02d".format(hour, minute) }

    private fun save() {
        val name = nameEt.text.toString().trim()
        val src = srcEt.text.toString().trim().trimEnd('/')
        val remote = remoteDd.text.toString().trim()
        val isInterval = trigGroup.checkedButtonId == R.id.tInterval
        val interval = intervalEt.text.toString().toIntOrNull() ?: 0
        val days = (0..6).fold(0) { a, i -> if ((daysGroup.getChildAt(i) as Chip).isChecked) a or (1 shl i) else a }

        if (name.isEmpty() || src.isEmpty() || remote.isEmpty()) return toast("Name, source and remote are required")
        if (isInterval && interval < 15) return toast("Minimum interval is 15 minutes")
        if (!isInterval && days == 0) return toast("Select at least one day")

        val job = Job(
            id = editing?.id ?: UUID.randomUUID().toString().take(8),
            name = name, source = src, remote = remote,
            remotePath = pathEt.text.toString().trim().trim('/'),
            sync = modeGroup.checkedButtonId == R.id.mSync,
            trigger = if (isInterval) TRIGGER_INTERVAL else TRIGGER_TIME,
            intervalMin = if (interval >= 15) interval else 60,
            hour = hour, minute = minute, days = days,
            wifiOnly = wifiSw.isChecked, chargingOnly = chargeSw.isChecked,
            exclude = excludeEt.text.toString().trim(), include = includeEt.text.toString().trim(),
            transfers = (transfersEt.text.toString().toIntOrNull() ?: 4).coerceIn(1, 8),
            enabled = editing?.enabled ?: true,
            lastRun = editing?.lastRun ?: 0, lastStatus = editing?.lastStatus ?: ""
        )
        JobStore.save(this, job)
        Scheduler.schedule(this, job)
        finish()
    }

    private fun confirmDelete(j: Job) {
        MaterialAlertDialogBuilder(this).setTitle("Delete job?").setMessage(j.name)
            .setPositiveButton("Delete") { _, _ ->
                Scheduler.cancel(this, j.id); JobStore.delete(this, j.id); JobLog.clear(this, j.id); finish()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
