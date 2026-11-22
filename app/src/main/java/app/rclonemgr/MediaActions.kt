package app.rclonemgr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File

/** Actions shared by the image viewer and the video player (the "⋮" menu). */
object MediaActions {
    /** Opens the file browser in "pick a folder" mode and returns the chosen location. */
    class PickFolder : ActivityResultContract<Unit, Loc?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, FilesActivity::class.java).putExtra("pick", true)

        override fun parseResult(resultCode: Int, intent: Intent?): Loc? =
            if (resultCode == Activity.RESULT_OK && intent != null)
                Loc(intent.getStringExtra("remote")?.ifEmpty { null }, intent.getStringExtra("path") ?: "")
            else null
    }

    private fun describe(l: Loc) = if (l.remote == null) l.path else "${l.remote}:/${l.path}"

    fun about(a: Activity, loc: Loc, name: String, extra: String?) {
        Thread {
            var size = -1L
            var mod = 0L
            try {
                if (loc.remote == null) {
                    val f = File(loc.path, name)
                    size = f.length(); mod = f.lastModified()
                } else {
                    val item = Rc.post("operations/stat", JSONObject().put("fs", loc.fs()).put("remote", name))
                        .optJSONObject("item")
                    if (item != null) {
                        size = item.optLong("Size", -1L)
                        mod = Rc.parseTime(item.optString("ModTime"))
                    }
                }
            } catch (_: Exception) {
            }
            val sb = StringBuilder()
            sb.append("Name: ").append(name).append('\n')
            sb.append("Location: ").append(describe(loc)).append('\n')
            if (size >= 0) sb.append("Size: ").append(Formatter.formatShortFileSize(a, size)).append(" (").append(size).append(" bytes)\n")
            if (mod > 0) sb.append("Modified: ").append(
                DateUtils.formatDateTime(a, mod, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
            ).append('\n')
            if (extra != null) sb.append(extra)
            a.runOnUiThread {
                if (!a.isDestroyed)
                    MaterialAlertDialogBuilder(a).setTitle("About").setMessage(sb.toString().trim())
                        .setPositiveButton("Close", null).show()
            }
        }.start()
    }

    fun copyMove(a: Activity, src: Loc, name: String, dst: Loc, cut: Boolean, onDone: () -> Unit) {
        val rc = src.remote != null || dst.remote != null
        OpRunner.run(a, if (cut) "Moving…" else "Copying…", rc, { c, p ->
            Ops.paste(src, Entry(name, 0L, false), dst, cut, c, p)
        }) { err ->
            if (err == null) {
                MediaHolder.changed = true
                Toast.makeText(a, if (cut) "Moved" else "Copied", Toast.LENGTH_SHORT).show()
                onDone()
            } else Toast.makeText(a, err, Toast.LENGTH_LONG).show()
        }
    }

    fun delete(a: Activity, loc: Loc, name: String, onDone: () -> Unit) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Delete file")
            .setMessage("Delete \"$name\" from ${if (loc.remote == null) "this device" else "the remote"}? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                OpRunner.run(a, "Deleting…", loc.remote != null, { c, p ->
                    Ops.delete(loc, Entry(name, 0L, false), c, p)
                }) { err ->
                    if (err == null) {
                        MediaHolder.changed = true
                        onDone()
                    } else Toast.makeText(a, err, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Copies a local file to the remembered backup folder (asks for one the first time). */
    fun backup(a: Activity, src: Loc, name: String, askFolder: ((Loc) -> Unit) -> Unit) {
        val saved = Prefs.backupDest(a)
        val go = { d: Loc ->
            Prefs.setBackupDest(a, d)
            copyMove(a, src, name, d, false) { }
        }
        if (saved == null) askFolder { d -> go(d) }
        else MaterialAlertDialogBuilder(a)
            .setTitle("Back up now")
            .setMessage("Copy \"$name\" to ${describe(saved)}?")
            .setPositiveButton("Back up") { _, _ -> go(saved) }
            .setNeutralButton("Change folder") { _, _ -> askFolder { d -> go(d) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens the system screen-cast panel (mirrors the screen to a TV; no Google Cast SDK is bundled). */
    fun cast(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(a, "Cast is not available on this device", Toast.LENGTH_SHORT).show()
        }
    }
}
