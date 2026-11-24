package app.rclonemgr

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

class FileAdapter(
    private val locOf: () -> Loc,
    private val selected: () -> Set<String>,
    private val menuOn: () -> Boolean,
    private val onClick: (Entry) -> Unit,
    private val onLong: (Entry) -> Unit,
    private val onMenu: (Entry, View) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    var items: List<Entry> = emptyList()
        private set
    private var grid = false

    fun submit(l: List<Entry>, g: Boolean) { items = l; grid = g; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (grid) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
        LayoutInflater.from(parent.context)
            .inflate(if (viewType == 1) R.layout.item_file_grid else R.layout.item_file_list, parent, false)
    )

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val ctx = h.itemView.context
        h.name.text = e.name
        h.desc?.text = describe(e, h.itemView)
        val sel = e.name in selected()
        h.itemView.isActivated = sel
        h.check.visibility = if (sel) View.VISIBLE else View.GONE
        h.badge?.visibility = if (e.kind == Entry.K_VIDEO) View.VISIBLE else View.GONE

        h.img.tag = null
        h.img.scaleType = ImageView.ScaleType.FIT_CENTER
        val tint = MaterialColors.getColor(
            h.img,
            if (e.isDir) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        h.img.imageTintList = ColorStateList.valueOf(tint)
        val pad = ctx.dp(if (grid) 32 else 8)
        h.img.setPadding(pad, pad, pad, pad)
        h.img.setImageResource(
            when (e.kind) {
                Entry.K_DIR -> R.drawable.ic_folder
                Entry.K_IMAGE -> R.drawable.ic_image
                Entry.K_VIDEO -> R.drawable.ic_movie
                Entry.K_AUDIO -> R.drawable.ic_audio
                Entry.K_ARCHIVE -> R.drawable.ic_archive
                Entry.K_DOC -> R.drawable.ic_doc
                else -> R.drawable.ic_file
            }
        )
        if (e.kind == Entry.K_IMAGE || e.kind == Entry.K_VIDEO) Thumbs.load(h.img, locOf(), e)

        h.itemView.setOnClickListener { onClick(e) }
        h.itemView.setOnLongClickListener { onLong(e); true }
        h.menu?.visibility = if (menuOn() && selected().isEmpty()) View.VISIBLE else View.GONE
        h.menu?.setOnClickListener { onMenu(e, it) }
    }

    private fun describe(e: Entry, v: View): String {
        val date = if (e.modified > 0)
            DateUtils.formatDateTime(v.context, e.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)
        else ""
        return if (e.isDir) date else listOf(Formatter.formatShortFileSize(v.context, e.size), date)
            .filter { it.isNotEmpty() }.joinToString(", ")
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val name: TextView = v.findViewById(R.id.name)
        val desc: TextView? = v.findViewById(R.id.desc)
        val check: View = v.findViewById(R.id.check)
        val badge: View? = v.findViewById(R.id.badge)
        val menu: View? = v.findViewById(R.id.menuBtn)
    }
}
