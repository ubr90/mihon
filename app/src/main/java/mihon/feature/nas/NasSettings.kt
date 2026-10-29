package mihon.feature.nas

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Custom: settings for the NAS (WebDAV) folder browser.
 */
class NasSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nas_browser", Context.MODE_PRIVATE)

    /** Server address, e.g. https://example.synology.me:5006 */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SERVER, value.trim().trimEnd('/')) }

    var username: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_USER, value) }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_PASSWORD, value) }

    /** Folder that is shown first, e.g. /pComic/comic */
    var rootPath: String
        get() = normalizePath(prefs.getString(KEY_ROOT, "/").orEmpty())
        set(value) = prefs.edit { putString(KEY_ROOT, normalizePath(value)) }

    /** File name of thumbnail images, hidden from listings and skipped inside archives. */
    var thumbName: String
        get() = prefs.getString(KEY_THUMB_NAME, DEFAULT_THUMB_NAME).orEmpty().ifBlank { DEFAULT_THUMB_NAME }
        set(value) = prefs.edit { putString(KEY_THUMB_NAME, value.trim()) }

    /** NAS folder for thumbnail backups, e.g. /pComic/_thumbs */
    var backupPath: String
        get() = prefs.getString(KEY_BACKUP, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_BACKUP, if (value.isBlank()) "" else normalizePath(value)) }

    /** Longest side of generated thumbnails in pixels. */
    var thumbSize: Int
        get() = prefs.getInt(KEY_THUMB_SIZE, 700)
        set(value) = prefs.edit { putInt(KEY_THUMB_SIZE, value) }

    /** Folder path stack of the browser, kept so going back works after the screen is recreated. */
    var lastStack: List<String>
        get() = prefs.getString(KEY_LAST_STACK, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_LAST_STACK, value.joinToString("\n")) }

    /** Favorite NAS folder paths (in the order they were added). */
    var favorites: List<String>
        get() = prefs.getString(KEY_FAVORITES, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_FAVORITES, value.joinToString("\n")) }

    /** Restore: download only thumbnails/metadata the phone does not have yet. */
    var restoreMissingOnly: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_MISSING_ONLY, true)
        set(value) = prefs.edit { putBoolean(KEY_RESTORE_MISSING_ONLY, value) }

    val isConfigured: Boolean
        get() = serverUrl.startsWith("http://") || serverUrl.startsWith("https://")

    companion object {
        private const val KEY_SERVER = "server"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ROOT = "root"
        private const val KEY_THUMB_NAME = "thumb_name"
        private const val KEY_BACKUP = "backup"
        private const val KEY_THUMB_SIZE = "thumb_size"
        private const val KEY_LAST_STACK = "last_stack"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RESTORE_MISSING_ONLY = "restore_missing_only"
        const val DEFAULT_THUMB_NAME = "000_cover.jpeg"

        fun normalizePath(path: String): String {
            val p = "/" + path.trim().trim('/')
            return if (p == "/") "/" else p
        }
    }
}

fun parentPath(path: String): String {
    val p = path.trimEnd('/')
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}

fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

fun childPath(parent: String, name: String): String =
    if (parent == "/") "/$name" else "$parent/$name"

private val ArchiveExtensions = setOf("zip", "cbz")
private val ImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heif", "heic")

fun isArchiveName(name: String) = name.substringAfterLast('.', "").lowercase() in ArchiveExtensions
fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in ImageExtensions

/** Natural order: "2권" before "10권". */
val NaturalOrder: Comparator<String> = Comparator { a, b ->
    val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
    val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val n = x.trimStart('0').length.compareTo(y.trimStart('0').length)
            if (n != 0) n else x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            x.compareTo(y)
        }
        if (c != 0) return@Comparator c
    }
    ra.size.compareTo(rb.size)
}
