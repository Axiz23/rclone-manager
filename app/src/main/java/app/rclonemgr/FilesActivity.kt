package app.rclonemgr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.concurrent.Executors

/** Main screen: file browser for internal storage (home) and rclone remotes, with a navigation drawer. */
class FilesActivity : BaseActivity() {
    private class Base(val title: String, val path: String)

    private lateinit var drawer: DrawerLayout
    private lateinit var tb: MaterialToolbar
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var stateBox: View
    private lateinit var stateText: TextView
    private lateinit var grantBtn: Button
    private lateinit var navList: RecyclerView
    private lateinit var adapter: FileAdapter
    private lateinit var navAdapter: NavAdapter

    private var bases: List<Base> = emptyList()
    private var loc = Loc(null, Environment.getExternalStorageDirectory().path)
    private var entries: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var thumbDir = false
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private var rc = false
    private var permShown = false
    private var loadId = 0
    private val ioList = Executors.newFixedThreadPool(2)
    private val ioOps = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var searchItem: MenuItem? = null

    private val pickConf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importConf) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_files)
        applyInsets(findViewById(R.id.root), findViewById(R.id.appbar))
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.navFrame)) { v, i ->
            val s = i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, s.top, 0, s.bottom)
            WindowInsetsCompat.CONSUMED
        }
        drawer = findViewById(R.id.drawer); tb = findViewById(R.id.toolbar)
        crumbs = findViewById(R.id.crumbs); crumbScroll = findViewById(R.id.crumbScroll)
        swipe = findViewById(R.id.swipe); rv = findViewById(R.id.rv)
        stateBox = findViewById(R.id.stateBox); stateText = findViewById(R.id.stateText); grantBtn = findViewById(R.id.grantBtn)
        navList = findViewById(R.id.navList)

        adapter = FileAdapter({ loc }, { thumbDir }, { selected }, { open(it) }, { onItemLong(it) }, { e, v -> showItemMenu(e, v) })
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        applyMode()
        swipe.setOnRefreshListener { load() }

        navAdapter = NavAdapter({ loc }) { onNavClick(it) }
        navList.layoutManager = LinearLayoutManager(this)
        navList.adapter = navAdapter
        buildNav()

        tb.setNavigationOnClickListener { onNavIcon() }
        tb.setOnMenuItemClickListener { onMenu(it.itemId) }
        setMode(false)
        grantBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { onBack() }
        })
        navigate(loc)
    }

    override fun onStart() {
        super.onStart()
        buildNav()
        if (permShown && Environment.isExternalStorageManager()) load()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        (rv.layoutManager as? GridLayoutManager)?.spanCount = spanCount()
    }

    // ------------------------------------------------------------------ navigation

    private fun onNavIcon() { drawer.openDrawer(GravityCompat.START) }

    private fun onBack() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            searchItem?.isActionViewExpanded == true -> searchItem?.collapseActionView()
            else -> { val p = parentOf(loc); if (p != null) navigate(p) else finish() }
        }
    }

    private fun parentOf(l: Loc): Loc? {
        if (l.remote != null) return if (l.path.isEmpty()) null else Loc(l.remote, l.path.substringBeforeLast('/', ""))
        if (l.path == "/" || bases.any { it.path == l.path }) return null
        val p = File(l.path).parent ?: return null
        return Loc(null, p)
    }

    private fun titleOf(l: Loc): String {
        if (l.remote != null) return if (l.path.isEmpty()) l.remote else l.path.substringAfterLast('/')
        return bases.firstOrNull { it.path == l.path }?.title ?: File(l.path).name.ifEmpty { "/" }
    }

    private fun navigate(l: Loc) {
        searchItem?.collapseActionView()
        if (l.remote != null) needRc() else dropRc()
        loc = l
        selected.clear()
        buildCrumbs()
        navAdapter.notifyDataSetChanged()
        load(true)
    }

    private fun needRc() { if (!rc) { RcServer.acquire(this); rc = true } }
    private fun dropRc() { if (rc) { RcServer.release(); rc = false } }

    private fun onNavClick(item: NavItem) {
        drawer.closeDrawer(GravityCompat.START)
        val l = item.loc
        if (l != null) navigate(l) else item.action?.invoke()
    }

    private fun spaceText(d: File): String =
        "${Formatter.formatShortFileSize(this, d.freeSpace)} free of ${Formatter.formatShortFileSize(this, d.totalSpace)}"

    private fun buildNav() {
        val items = ArrayList<NavItem>()
        val bs = ArrayList<Base>()
        val sm = getSystemService(StorageManager::class.java)
        for (v in sm.storageVolumes) {
            val dir = v.directory ?: continue
            if (v.state != Environment.MEDIA_MOUNTED) continue
            val title = if (v.isPrimary) "Internal storage" else (v.getDescription(this) ?: "Storage")
            bs.add(Base(title, dir.path))
            items.add(NavItem(NavItem.ITEM, if (v.isPrimary) R.drawable.ic_phone else R.drawable.ic_sd, title, spaceText(dir), Loc(null, dir.path)))
        }
        if (bs.isEmpty()) {
            val p = Environment.getExternalStorageDirectory()
            bs.add(Base("Internal storage", p.path))
            items.add(NavItem(NavItem.ITEM, R.drawable.ic_phone, "Internal storage", spaceText(p), Loc(null, p.path)))
        }
        bases = bs

        val root = bs[0].path
        val std = listOf(
            Triple("Camera", "DCIM", R.drawable.ic_image),
            Triple("Downloads", "Download", R.drawable.ic_download),
            Triple("Documents", "Documents", R.drawable.ic_doc),
            Triple("Movies", "Movies", R.drawable.ic_movie),
            Triple("Music", "Music", R.drawable.ic_audio),
            Triple("Pictures", "Pictures", R.drawable.ic_image)
        ).filter { File(root, it.second).isDirectory }
        if (std.isNotEmpty()) {
            items.add(NavItem(NavItem.DIVIDER))
            for ((t, d, ic) in std) items.add(NavItem(NavItem.ITEM, ic, t, null, Loc(null, "$root/$d")))
        }

        items.add(NavItem(NavItem.DIVIDER))
        items.add(NavItem(NavItem.HEADER, title = "Remotes"))
        for ((n, t) in RcloneRunner.remoteInfo(this))
            items.add(NavItem(NavItem.ITEM, R.drawable.ic_cloud, n, t.ifEmpty { null }, Loc(n, "")))
        items.add(NavItem(NavItem.ITEM, R.drawable.ic_add, "Import rclone.conf", action = { pickConf.launch(arrayOf("*/*")) }))

        items.add(NavItem(NavItem.DIVIDER))
        items.add(NavItem(NavItem.ITEM, R.drawable.ic_schedule, "Backup jobs", action = {
            startActivity(Intent(this, JobsActivity::class.java))
        }))
        items.add(NavItem(NavItem.ITEM, R.drawable.ic_settings, "Settings", action = { Dialogs.settings(this) }))
        navAdapter.items = items
        navAdapter.notifyDataSetChanged()
    }

    private fun importConf(uri: Uri) {
        try {
            val dst = RcloneRunner.confFile(this)
            val tmp = File(dst.path + ".tmp")
            contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(dst)) throw IOException("rename failed")
            toast("${RcloneRunner.remotes(this).size} remote(s) found")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
        buildNav()
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        val segs = ArrayList<Pair<String, Loc>>()
        if (loc.remote != null) {
            segs.add(loc.remote!! to Loc(loc.remote, ""))
            var acc = ""
            for (p in loc.path.split('/').filter { it.isNotEmpty() }) {
                acc = if (acc.isEmpty()) p else "$acc/$p"
                segs.add(p to Loc(loc.remote, acc))
            }
        } else {
            val base = bases.filter { loc.path == it.path || loc.path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
            val basePath = base?.path ?: "/"
            segs.add((base?.title ?: "/") to Loc(null, basePath))
            var acc = basePath
            for (p in loc.path.removePrefix(basePath).split('/').filter { it.isNotEmpty() }) {
                acc = if (acc == "/") "/$p" else "$acc/$p"
                segs.add(p to Loc(null, acc))
            }
        }
        segs.forEachIndexed { i, (label, l) ->
            val v = layoutInflater.inflate(R.layout.item_crumb, crumbs, false)
            val last = i == segs.size - 1
            val tv = v.findViewById<TextView>(R.id.label)
            tv.text = label
            tv.alpha = if (last) 1f else 0.7f
            v.findViewById<ImageView>(R.id.arrow).visibility = if (last) View.GONE else View.VISIBLE
            tv.setOnClickListener { if (!last) navigate(l) }
            crumbs.addView(v)
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ------------------------------------------------------------------ toolbar / menu

    private fun setMode(@Suppress("UNUSED_PARAMETER") sel: Boolean) {
        tb.menu.clear()
        tb.inflateMenu(R.menu.files_main)
        tb.setNavigationIcon(R.drawable.ic_menu)
        setupSearch()
        syncMenu()
        updateTitle()
    }

    private fun setupSearch() {
        val item = tb.menu.findItem(R.id.action_search) ?: return
        searchItem = item
        val sv = item.actionView as SearchView
        sv.queryHint = "Search in this folder"
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean { query = q ?: ""; applyFilter(); return true }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(i: MenuItem) = true
            override fun onMenuItemActionCollapse(i: MenuItem): Boolean { query = ""; applyFilter(); return true }
        })
    }

    private fun syncMenu() {
        val m = tb.menu
        m.findItem(R.id.action_view_list)?.isChecked = !Prefs.grid(this)
        m.findItem(R.id.action_view_grid)?.isChecked = Prefs.grid(this)
        val sb = Prefs.sortBy(this)
        m.findItem(R.id.action_sort_name)?.isChecked = sb == 0
        m.findItem(R.id.action_sort_type)?.isChecked = sb == 1
        m.findItem(R.id.action_sort_size)?.isChecked = sb == 2
        m.findItem(R.id.action_sort_modified)?.isChecked = sb == 3
        m.findItem(R.id.action_sort_asc)?.isChecked = Prefs.sortAsc(this)
        m.findItem(R.id.action_sort_dirs)?.isChecked = Prefs.dirsFirst(this)
        m.findItem(R.id.action_show_hidden)?.isChecked = Prefs.showHidden(this)
    }

    private fun updateTitle() {
        tb.title = titleOf(loc)
        tb.subtitle = if (shown.size == 1) "1 item" else "${shown.size} items"
    }

    private fun onMenu(id: Int): Boolean {
        when (id) {
            R.id.action_view_list -> { Prefs.setGrid(this, false); applyMode() }
            R.id.action_view_grid -> { Prefs.setGrid(this, true); applyMode() }
            R.id.action_sort_name -> sortBy(0)
            R.id.action_sort_type -> sortBy(1)
            R.id.action_sort_size -> sortBy(2)
            R.id.action_sort_modified -> sortBy(3)
            R.id.action_sort_asc -> { Prefs.setSortAsc(this, !Prefs.sortAsc(this)); applyFilter() }
            R.id.action_sort_dirs -> { Prefs.setDirsFirst(this, !Prefs.dirsFirst(this)); applyFilter() }
            R.id.action_show_hidden -> { Prefs.setShowHidden(this, !Prefs.showHidden(this)); applyFilter() }
            R.id.action_refresh -> load()
            R.id.action_copy_path -> copyPath()
            // stage2: menu branches
            else -> return false
        }
        syncMenu()
        return true
    }

    private fun sortBy(mode: Int) { Prefs.setSortBy(this, mode); applyFilter() }

    private fun copyPath() {
        val text = if (loc.remote == null) loc.path else "${loc.remote}:${loc.path}"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", text))
        toast("Path copied")
    }

    // ------------------------------------------------------------------ list

    private fun spanCount(): Int =
        (resources.displayMetrics.widthPixels / (140 * resources.displayMetrics.density)).toInt().coerceAtLeast(2)

    private fun applyMode() {
        val g = Prefs.grid(this)
        rv.layoutManager = if (g) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
        adapter.submit(adapter.items, g)
    }

    private fun comparator(): Comparator<Entry> {
        val col = Collator.getInstance()
        val byName = Comparator<Entry> { a, b -> col.compare(a.name, b.name) }
        var cmp: Comparator<Entry> = when (Prefs.sortBy(this)) {
            1 -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.ext }.then(byName)
            2 -> compareBy<Entry> { it.size }.then(byName)
            3 -> compareBy<Entry> { it.modified }.then(byName)
            else -> byName
        }
        if (!Prefs.sortAsc(this)) cmp = cmp.reversed()
        if (Prefs.dirsFirst(this)) cmp = compareBy<Entry> { !it.isDir }.then(cmp)
        return cmp
    }

    private fun applyFilter() {
        val hidden = Prefs.showHidden(this)
        val q = query.trim().lowercase()
        val l = entries
            .filter { (hidden || !it.name.startsWith(".")) && (q.isEmpty() || it.name.lowercase().contains(q)) }
            .sortedWith(comparator())
        shown = l
        selected.retainAll(l.map { it.name }.toSet())
        adapter.submit(l, Prefs.grid(this))
        updateTitle()
        showState(if (l.isEmpty()) (if (q.isEmpty()) "This folder is empty" else "No results") else null)
    }

    private fun showState(text: String?) {
        stateBox.visibility = if (text == null) View.GONE else View.VISIBLE
        stateText.text = text ?: ""
        grantBtn.visibility = View.GONE
    }

    private fun showPermission() {
        permShown = true
        swipe.isRefreshing = false
        entries = emptyList(); shown = emptyList()
        adapter.submit(emptyList(), Prefs.grid(this))
        stateBox.visibility = View.VISIBLE
        stateText.text = "Allow access to all files to browse this device's storage."
        grantBtn.visibility = View.VISIBLE
        updateTitle()
    }

    private fun load(reset: Boolean = false) {
        val id = ++loadId
        val l = loc
        if (l.remote == null && !Environment.isExternalStorageManager()) { showPermission(); return }
        permShown = false
        swipe.isRefreshing = true
        showState(null)
        Thumbs.cancelAll()
        ioList.execute {
            try {
                val list: List<Entry>
                val td: Boolean
                if (l.remote == null) {
                    val d = File(l.path)
                    val fs = d.listFiles() ?: throw IOException(if (d.exists()) "Cannot read this folder" else "Folder not found")
                    list = fs.map { Entry(it.name, if (it.isDirectory) 0L else it.length(), it.isDirectory, it.lastModified()) }
                    td = true
                } else {
                    list = Rc.list(l.remote, l.path)
                    td = list.any { it.isDir && it.name == ".thumbnails" }
                }
                ui.post {
                    if (id == loadId) {
                        entries = list; thumbDir = td
                        swipe.isRefreshing = false
                        applyFilter()
                        if (reset) rv.scrollToPosition(0)
                    }
                }
            } catch (ex: Exception) {
                ui.post {
                    if (id == loadId) {
                        swipe.isRefreshing = false
                        entries = emptyList()
                        applyFilter()
                        showState(ex.message ?: "Error")
                    }
                }
            }
        }
    }

    private fun open(e: Entry) {
        val remote = loc.remote ?: ""
        when (e.kind) {
            Entry.K_DIR -> navigate(loc.child(e.name))
            Entry.K_IMAGE -> {
                val names = shown.filter { it.kind == Entry.K_IMAGE }.map { it.name }
                MediaHolder.names = names
                startActivity(
                    Intent(this, ImageActivity::class.java)
                        .putExtra("remote", remote).putExtra("dir", loc.path).putExtra("index", names.indexOf(e.name))
                )
            }
            Entry.K_VIDEO -> startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra("remote", remote).putExtra("dir", loc.path).putExtra("name", e.name)
            )
            else -> toast("No built-in viewer for this file type")
        }
    }

    // stage2 hooks (selection mode and file operations are added in stage 2)
    private fun onItemLong(e: Entry) {}
    private fun showItemMenu(e: Entry, v: View) {}

    // ---- stage2 insert point ----

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        Thumbs.cancelAll()
        ioList.shutdownNow()
        ioOps.shutdownNow()
        dropRc()
        super.onDestroy()
    }
}
