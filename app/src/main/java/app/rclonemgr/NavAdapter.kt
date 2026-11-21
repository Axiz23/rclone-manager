package app.rclonemgr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NavItem(
    val kind: Int,
    val icon: Int = 0,
    val title: String = "",
    val subtitle: String? = null,
    val loc: Loc? = null,
    val action: (() -> Unit)? = null
) {
    companion object {
        const val ITEM = 0
        const val DIVIDER = 1
        const val HEADER = 2
    }
}

class NavAdapter(
    private val current: () -> Loc,
    private val onClick: (NavItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<NavItem> = emptyList()

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = items[position].kind

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            NavItem.DIVIDER -> R.layout.item_nav_divider
            NavItem.HEADER -> R.layout.item_nav_header
            else -> R.layout.item_nav
        }
        return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val v = h.itemView
        when (item.kind) {
            NavItem.HEADER -> (v as TextView).text = item.title
            NavItem.ITEM -> {
                v.findViewById<ImageView>(R.id.icon).setImageResource(item.icon)
                v.findViewById<TextView>(R.id.title).text = item.title
                val st = v.findViewById<TextView>(R.id.subtitle)
                st.text = item.subtitle
                st.visibility = if (item.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                val cur = current()
                val l = item.loc
                v.isActivated = l != null && (l == cur || (cur.remote != null && l.remote == cur.remote))
                v.setOnClickListener { onClick(item) }
            }
        }
    }
}
