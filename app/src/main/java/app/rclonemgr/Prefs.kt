package app.rclonemgr

import android.content.Context

/** Global settings (shared by all screens). */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private fun get(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    private fun put(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun loop(c: Context) = get(c, "loop", true)
    fun setLoop(c: Context, v: Boolean) = put(c, "loop", v)
    fun grid(c: Context) = get(c, "grid", false)
    fun setGrid(c: Context, v: Boolean) = put(c, "grid", v)
    fun useThumbFolder(c: Context) = get(c, "thumbFolder", true)
    fun setUseThumbFolder(c: Context, v: Boolean) = put(c, "thumbFolder", v)
    fun generateThumbs(c: Context) = get(c, "generate", true)
    fun setGenerateThumbs(c: Context, v: Boolean) = put(c, "generate", v)
}
