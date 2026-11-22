package app.rclonemgr

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** In-memory diagnostics for thumbnail generation (shown from the drawer: "Thumbnail log"). */
object ThumbLog {
    private val lines = ArrayDeque<String>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun count(key: String) { counters.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() }

    @Synchronized
    fun add(s: String) {
        if (lines.size >= 80) lines.removeFirst()
        lines.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + s.take(300))
    }

    @Synchronized
    fun text(): String {
        fun c(k: String) = counters[k]?.get() ?: 0
        val head = "photos: queued ${c("img.queued")}, started ${c("img.started")}, ok ${c("img.ok")}, " +
            "failed ${c("img.fail")}, skipped ${c("img.stale")}\n" +
            "videos: queued ${c("vid.queued")}, started ${c("vid.started")}, ok ${c("vid.ok")}, " +
            "failed ${c("vid.fail")}, skipped ${c("vid.stale")}\n\n"
        return head + if (lines.isEmpty()) "No entries yet." else lines.joinToString("\n")
    }

    @Synchronized
    fun clear() { lines.clear(); counters.clear() }
}
