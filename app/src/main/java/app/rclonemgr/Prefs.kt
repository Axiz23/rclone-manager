package app.rclonemgr

import android.content.Context

/** Global settings (shared by all screens). */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    private fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun loop(c: Context) = bool(c, "loop", true)
    fun setLoop(c: Context, v: Boolean) = putBool(c, "loop", v)
    fun grid(c: Context) = bool(c, "grid", false)
    fun setGrid(c: Context, v: Boolean) = putBool(c, "grid", v)
    fun serverUrl(c: Context) = sp(c).getString("serverUrl", "") ?: ""
    fun token(c: Context) = sp(c).getString("token", "") ?: ""
    fun setServer(c: Context, url: String, token: String) =
        sp(c).edit().putString("serverUrl", url).putString("token", token).apply()
    fun adapter(c: Context): String? = sp(c).getString("adapter", null)
    fun setAdapter(c: Context, v: String?) = sp(c).edit().apply { if (v == null) remove("adapter") else putString("adapter", v) }.apply()
    /** Disk cache limit in MB for files + video (thumbnails have their own 100 MB). */
    fun cacheMb(c: Context) = sp(c).getInt("cacheMb", 300)
    fun setCacheMb(c: Context, v: Int) = sp(c).edit().putInt("cacheMb", v).apply()
    fun maxUpload(c: Context) = sp(c).getLong("maxUpload", 0L)
    fun setMaxUpload(c: Context, v: Long) = sp(c).edit().putLong("maxUpload", v).apply()
    fun remotes(c: Context): List<String> =
        (sp(c).getString("remotes", "") ?: "").split('\n').filter { it.isNotEmpty() }
    fun setRemotes(c: Context, l: List<String>) = sp(c).edit().putString("remotes", l.joinToString("\n")).apply()
    fun showHidden(c: Context) = bool(c, "hidden", false)
    fun setShowHidden(c: Context, v: Boolean) = putBool(c, "hidden", v)
    fun sortAsc(c: Context) = bool(c, "sortAsc", true)
    fun setSortAsc(c: Context, v: Boolean) = putBool(c, "sortAsc", v)
    fun dirsFirst(c: Context) = bool(c, "dirsFirst", true)
    fun setDirsFirst(c: Context, v: Boolean) = putBool(c, "dirsFirst", v)

    fun backupDest(c: Context): Loc? {
        val s = sp(c).getString("backupDest", null) ?: return null
        val i = s.indexOf('\u0001')
        if (i < 0) return null
        return Loc(s.substring(0, i).ifEmpty { null }, s.substring(i + 1))
    }
    fun setBackupDest(c: Context, l: Loc) =
        sp(c).edit().putString("backupDest", (l.remote ?: "") + "\u0001" + l.path).apply()

    /** 0 = name, 1 = type, 2 = size, 3 = last modified */
    fun sortBy(c: Context) = sp(c).getInt("sortBy", 0)
    fun setSortBy(c: Context, v: Int) = sp(c).edit().putInt("sortBy", v).apply()
}
