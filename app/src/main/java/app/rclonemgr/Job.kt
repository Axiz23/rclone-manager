package app.rclonemgr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val TRIGGER_INTERVAL = 0
const val TRIGGER_TIME = 1
val DAY_NAMES = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

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
    val days: Int,            // bit0 = Senin ... bit6 = Minggu
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    var enabled: Boolean,
    var lastRun: Long = 0,
    var lastStatus: String = ""
) {
    fun dest() = "$remote:$remotePath"

    fun triggerText(): String =
        if (trigger == TRIGGER_INTERVAL) "Setiap $intervalMin menit"
        else "%02d:%02d · %s".format(
            hour, minute,
            if (days == 0x7F) "setiap hari"
            else DAY_NAMES.filterIndexed { i, _ -> (days and (1 shl i)) != 0 }.joinToString(", ")
        )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("source", source)
        .put("remote", remote).put("remotePath", remotePath).put("sync", sync)
        .put("trigger", trigger).put("intervalMin", intervalMin)
        .put("hour", hour).put("minute", minute).put("days", days)
        .put("wifiOnly", wifiOnly).put("chargingOnly", chargingOnly)
        .put("enabled", enabled).put("lastRun", lastRun).put("lastStatus", lastStatus)

    companion object {
        fun fromJson(o: JSONObject) = Job(
            o.getString("id"), o.optString("name"), o.optString("source"),
            o.optString("remote"), o.optString("remotePath"), o.optBoolean("sync"),
            o.optInt("trigger"), o.optInt("intervalMin", 60),
            o.optInt("hour", 2), o.optInt("minute"), o.optInt("days", 0x7F),
            o.optBoolean("wifiOnly", true), o.optBoolean("chargingOnly"),
            o.optBoolean("enabled", true), o.optLong("lastRun"), o.optString("lastStatus")
        )
    }
}

/** Penyimpanan JSON sederhana: tanpa Room/DB supaya ringan. */
object JobStore {
    private var cache: MutableList<Job>? = null

    private fun file(c: Context) = File(c.applicationContext.filesDir, "jobs.json")

    @Synchronized
    private fun list(c: Context): MutableList<Job> {
        cache?.let { return it }
        val l = ArrayList<Job>()
        try {
            val f = file(c)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) l.add(Job.fromJson(a.getJSONObject(i)))
            }
        } catch (_: Exception) {
        }
        cache = l
        return l
    }

    @Synchronized fun all(c: Context): List<Job> = list(c).toList()

    @Synchronized fun get(c: Context, id: String): Job? = list(c).firstOrNull { it.id == id }

    @Synchronized
    fun save(c: Context, j: Job) {
        val l = list(c)
        val i = l.indexOfFirst { it.id == j.id }
        if (i >= 0) l[i] = j else l.add(j)
        persist(c)
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        list(c).removeAll { it.id == id }
        persist(c)
    }

    private fun persist(c: Context) {
        val a = JSONArray()
        list(c).forEach { a.put(it.toJson()) }
        val f = file(c)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }
}
