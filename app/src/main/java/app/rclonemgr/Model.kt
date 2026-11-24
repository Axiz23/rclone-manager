package app.rclonemgr

/** A directory: on the device (remote == null, absolute path) or on a server remote (path relative to the remote root). */
data class Loc(val remote: String?, val path: String) {
    fun child(name: String): Loc = Loc(
        remote,
        when {
            remote == null -> if (path == "/" || path.isEmpty()) "/$name" else "$path/$name"
            path.isEmpty() -> name
            else -> "$path/$name"
        }
    )
}

class Entry(val name: String, val size: Long, val isDir: Boolean, val modified: Long = 0L) {
    val ext: String = name.substringAfterLast('.', "").lowercase()
    val kind: Int = when {
        isDir -> K_DIR
        ext in IMG -> K_IMAGE
        ext in VID -> K_VIDEO
        ext in AUD -> K_AUDIO
        ext in ARC -> K_ARCHIVE
        ext in DOC -> K_DOC
        else -> K_FILE
    }

    companion object {
        const val K_DIR = 0
        const val K_IMAGE = 1
        const val K_VIDEO = 2
        const val K_FILE = 3
        const val K_AUDIO = 4
        const val K_ARCHIVE = 5
        const val K_DOC = 6
        private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VID = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi")
        private val AUD = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
        private val ARC = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst")
        private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json", "xml", "html", "log", "epub")
    }
}

/** Names of the images in the folder being viewed (avoids huge Intents). */
object MediaHolder {
    @Volatile var names: List<String> = emptyList()    // images in the current folder
    @Volatile var videos: List<String> = emptyList()   // videos in the current folder
    @Volatile var images: List<Entry> = emptyList()    // images in the current folder (with size/date for thumbnail lookup)
    @Volatile var changed = false                      // a viewer deleted/moved something -> file list must reload
}

/** Cut / copy clipboard shared by the file list. */
object Clipboard {
    var dir: Loc? = null
    var items: List<Entry> = emptyList()
    var cut = false
    fun clear() { dir = null; items = emptyList() }
}
