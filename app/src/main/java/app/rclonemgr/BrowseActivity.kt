package app.rclonemgr

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors

class FileAdapter(
    private val remote: String,
    private val dirOf: () -> String,
    private val onClick: (Entry) -> Unit
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
        h.sub?.text = if (e.isDir) "Folder" else Formatter.formatShortFileSize(ctx, e.size)
        h.badge?.visibility = if (e.kind == Entry.K_VIDEO) View.VISIBLE else View.GONE
        h.img.tag = null
        h.img.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = ((if (grid) 28 else 10) * ctx.resources.displayMetrics.density).toInt()
        h.img.setPadding(pad, pad, pad, pad)
        h.img.setImageResource(
            when (e.kind) {
                Entry.K_DIR -> R.drawable.ic_folder
                Entry.K_IMAGE -> R.drawable.ic_image
                Entry.K_VIDEO -> R.drawable.ic_movie
                else -> R.drawable.ic_file
            }
        )
        if (e.kind == Entry.K_IMAGE || e.kind == Entry.K_VIDEO) Thumbs.load(h.img, remote, dirOf(), e)
        h.itemView.setOnClickListener { onClick(e) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView? = v.findViewById(R.id.sub)
        val badge: View? = v.findViewById(R.id.badge)
    }
}

class BrowseActivity : BaseActivity() {
    private var remote = ""
    private var dir = ""                      // current path relative to the remote root
    private var acquired = false
    private var loadId = 0
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var rv: RecyclerView
    private lateinit var progress: View
    private lateinit var msg: TextView
    private lateinit var tb: MaterialToolbar
    private lateinit var adapter: FileAdapter

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_browse)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))
        remote = intent.getStringExtra("remote") ?: run { finish(); return }
        RcServer.acquire(this); acquired = true

        rv = findViewById(R.id.rv); progress = findViewById(R.id.progress); msg = findViewById(R.id.msg)
        tb = findViewById(R.id.toolbar)
        tb.title = remote
        tb.setNavigationOnClickListener { goUp() }
        tb.inflateMenu(R.menu.browse)
        tb.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_view -> { Prefs.setGrid(this, !Prefs.grid(this)); applyMode() }
                R.id.action_refresh -> load()
            }
            true
        }
        adapter = FileAdapter(remote, { dir }) { open(it) }
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        applyMode()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goUp() }
        })
        load()
    }

    private fun goUp() {
        if (dir.isEmpty()) { finish(); return }
        dir = dir.substringBeforeLast('/', "")
        load()
    }

    private fun spanCount(): Int =
        (resources.displayMetrics.widthPixels / (110 * resources.displayMetrics.density)).toInt().coerceAtLeast(2)

    private fun applyMode() {
        val g = Prefs.grid(this)
        rv.layoutManager = if (g) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
        adapter.submit(adapter.items, g)
        tb.menu.findItem(R.id.action_view)?.apply {
            setIcon(if (g) R.drawable.ic_list else R.drawable.ic_grid)
            title = if (g) "List view" else "Grid view"
        }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        (rv.layoutManager as? GridLayoutManager)?.spanCount = spanCount()
    }

    private fun load() {
        val id = ++loadId
        progress.visibility = View.VISIBLE
        msg.visibility = View.GONE
        Thumbs.cancelAll()
        tb.subtitle = "/$dir"
        val useFolder = Prefs.useThumbFolder(this)
        io.execute {
            try {
                val all = Rc.list(remote, dir)
                var thumbs: Map<String, String> = emptyMap()
                if (useFolder && all.any { it.isDir && it.name == ".thumbnails" }) {
                    thumbs = try {
                        index(Rc.list(remote, if (dir.isEmpty()) ".thumbnails" else "$dir/.thumbnails"))
                    } catch (_: Exception) { emptyMap() }
                }
                val list = all.filter { !(it.isDir && it.name == ".thumbnails") }
                    .sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
                if (thumbs.isNotEmpty()) {
                    for (e in list) if (e.kind == Entry.K_IMAGE || e.kind == Entry.K_VIDEO)
                        e.thumbName = thumbs[e.name] ?: thumbs[e.name.substringBeforeLast('.')]
                }
                ui.post { if (id == loadId) showList(list) }
            } catch (ex: Exception) {
                ui.post { if (id == loadId) showError(ex.message ?: "Error") }
            }
        }
    }

    /** thumbnail file name -> keys "name" and "name without extension" (e.g. IMG_1.jpg.jpg, IMG_1.png). */
    private fun index(t: List<Entry>): Map<String, String> {
        val m = HashMap<String, String>()
        for (e in t) if (!e.isDir) {
            m[e.name] = e.name
            m.putIfAbsent(e.name.substringBeforeLast('.'), e.name)
        }
        return m
    }

    private fun showList(l: List<Entry>) {
        progress.visibility = View.GONE
        adapter.submit(l, Prefs.grid(this))
        rv.scrollToPosition(0)
        msg.text = "Empty folder"
        msg.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showError(m: String) {
        progress.visibility = View.GONE
        adapter.submit(emptyList(), Prefs.grid(this))
        msg.text = m
        msg.visibility = View.VISIBLE
    }

    private fun open(e: Entry) {
        when (e.kind) {
            Entry.K_DIR -> { dir = if (dir.isEmpty()) e.name else "$dir/${e.name}"; load() }
            Entry.K_IMAGE -> {
                val names = adapter.items.filter { it.kind == Entry.K_IMAGE }.map { it.name }
                MediaHolder.names = names
                startActivity(
                    Intent(this, ImageActivity::class.java)
                        .putExtra("remote", remote).putExtra("dir", dir).putExtra("index", names.indexOf(e.name))
                )
            }
            Entry.K_VIDEO -> startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra("remote", remote).putExtra("dir", dir).putExtra("name", e.name)
            )
            else -> Toast.makeText(this, "No built-in viewer for this file type", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        Thumbs.cancelAll()
        io.shutdownNow()
        if (acquired) RcServer.release()
        super.onDestroy()
    }
}
