package app.rclonemgr

import android.content.Context
import org.json.JSONObject

const val TRIGGER_INTERVAL = 0
const val TRIGGER_TIME = 1
val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** A backup job: what to back up, where to, when to run, and the result of the last run. */
class Job(
    val id: String,
    val name: String,
    val source: String,
    val remote: String,
    val remotePath: String,
    val sync: Boolean,
    val trigger: Int,
    val intervalMin: Int,
    val hour: Int,
    val minute: Int,
    val days: Int,              // bit 0 = Monday ... bit 6 = Sunday
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val exclude: String = "",   // one pattern per line
    val include: String = "",   // one pattern per line (optional)
    val transfers: Int = 4,     // parallel transfers, 1..8
    var enabled: Boolean = true,
    var lastRun: Long = 0,
    var lastStatus: String = ""
) {
    fun dest() = "$remote:$remotePath"

    fun triggerText(): String =
        if (trigger == TRIGGER_INTERVAL) "Every $intervalMin min"
        else "%02d:%02d · %s".format(
            hour, minute,
            if (days == 0x7F) "every day"
            else DAY_NAMES.filterIndexed { i, _ -> (days and (1 shl i)) != 0 }.joinToString(", ")
        )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("source", source)
        .put("remote", remote).put("remotePath", remotePath).put("sync", sync)
        .put("trigger", trigger).put("intervalMin", intervalMin)
        .put("hour", hour).put("minute", minute).put("days", days)
        .put("wifiOnly", wifiOnly).put("chargingOnly", chargingOnly)
        .put("exclude", exclude).put("include", include).put("transfers", transfers)
        .put("enabled", enabled).put("lastRun", lastRun).put("lastStatus", lastStatus)

    companion object {
        fun fromJson(o: JSONObject) = Job(
            id = o.getString("id"), name = o.optString("name"), source = o.optString("source"),
            remote = o.optString("remote"), remotePath = o.optString("remotePath"),
            sync = o.optBoolean("sync"), trigger = o.optInt("trigger"),
            intervalMin = o.optInt("intervalMin", 60), hour = o.optInt("hour", 2),
            minute = o.optInt("minute"), days = o.optInt("days", 0x7F),
            wifiOnly = o.optBoolean("wifiOnly", true), chargingOnly = o.optBoolean("chargingOnly"),
            exclude = o.optString("exclude"), include = o.optString("include"),
            transfers = o.optInt("transfers", 4).coerceIn(1, 8),
            enabled = o.optBoolean("enabled", true), lastRun = o.optLong("lastRun"),
            lastStatus = o.optString("lastStatus")
        )
    }
}

/**
 * Job storage (`filesDir/jobs.json`), kept by the Rust core. Every call returns fresh [Job] objects: change a job
 * and call [save] to store it.
 */
object JobStore {
    private fun init(c: Context) = Core.ensureInit(c)

    fun all(c: Context): List<Job> {
        init(c)
        val a = Core.array("jobs.all")
        return List(a.length()) { Job.fromJson(a.getJSONObject(it)) }
    }

    fun get(c: Context, id: String): Job? {
        init(c)
        return (Core.run("jobs.get", Core.args("id" to id)) as? JSONObject)?.let { Job.fromJson(it) }
    }

    fun save(c: Context, j: Job) {
        init(c)
        Core.run("jobs.save", j.toJson())
    }

    fun delete(c: Context, id: String) {
        init(c)
        Core.run("jobs.delete", Core.args("id" to id))
    }
}
