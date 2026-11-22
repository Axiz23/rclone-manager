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
    fun useThumbFolder(c: Context) = bool(c, "thumbFolder", true)
    fun setUseThumbFolder(c: Context, v: Boolean) = putBool(c, "thumbFolder", v)
    fun generateThumbs(c: Context) = bool(c, "generate", true)
    fun setGenerateThumbs(c: Context, v: Boolean) = putBool(c, "generate", v)
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
