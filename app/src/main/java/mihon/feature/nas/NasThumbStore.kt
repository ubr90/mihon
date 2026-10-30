package mihon.feature.nas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Custom: thumbnails for NAS folders and archives, stored on the phone.
 * The index (path -> file) lets backups mirror the NAS folder structure.
 */
class NasThumbStore(context: Context) {

    @Serializable
    data class Item(val path: String, val folder: Boolean)

    private val dir = File(context.filesDir, "nas_thumbs").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val indexSerializer = MapSerializer(String.serializer(), Item.serializer())

    private val index: MutableMap<String, Item> = runCatching {
        json.decodeFromString(indexSerializer, indexFile.readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())

    // Custom (2026-10-31): the one-time renaming of old thumbnail names ran on every start and froze the
    // NAS tab on large libraries; it is done, so only the index is read now. Leaf learning is no longer
    // needed (every folder's "[memo]" is ignored), the calls are kept as no-ops.

    /** No longer needed (every folder's memo is ignored); kept so callers stay simple. */
    fun markLeaf(@Suppress("UNUSED_PARAMETER") folderPath: String) = Unit

    /** Memo-free path of [folderPath]. */
    fun canonAsLeaf(folderPath: String): String = canonDir(folderPath, forceLast = true)

    /** No longer needed; kept as a no-op. */
    fun learnLeaves(@Suppress("UNUSED_PARAMETER") items: List<Item>) = Unit

    /**
     * Folder path with the "[memo]" removed from every folder that directly holds archives
     * (same rule as the PC tool). The first folder (the shared folder) is never changed.
     */
    private fun canonDir(path: String, forceLast: Boolean): String {
        // 2026-10-30 rule: the "[memo]" of every folder is ignored (except the shared folder)
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        return segments.mapIndexed { i, segment -> if (i > 0) stripMemo(segment) else segment }
            .joinToString("/", prefix = "/")
            .let { if (segments.isEmpty()) "/" else it }
    }

    /**
     * Path used for matching and for the backup on the NAS: the memo of folders holding archives is ignored,
     * so "강철 [E북]" and "강철 [E-book]" are the same. Archive names and the TOC part stay as they are.
     */
    fun canon(path: String, folder: Boolean): String = when {
        '#' in path -> canon(path.substringBefore('#'), folder = false) + "#" + path.substringAfter('#')
        !folder -> childPath(canonDir(parentPath(path), forceLast = true), fileName(path))
        else -> canonDir(path, forceLast = false)
    }

    /** Bumped on every change so the UI reloads tiles. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun rawKey(path: String, folder: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(((if (folder) "d:" else "f:") + path).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun key(path: String, folder: Boolean): String = rawKey(canon(path, folder), folder)

    /** The stored file: memo-free name first, then the exact name (folder not known to hold archives yet). */
    fun file(path: String, folder: Boolean): File {
        val main = File(dir, key(path, folder) + ".jpg")
        if (main.exists()) return main
        val exact = File(dir, rawKey(path, folder) + ".jpg")
        return if (exact.exists()) exact else main
    }

    fun get(path: String, folder: Boolean): File? = file(path, folder).takeIf { it.exists() }

    fun has(path: String, folder: Boolean): Boolean = file(path, folder).exists()

    @Synchronized
    fun put(
        path: String,
        folder: Boolean,
        imageBytes: ByteArray,
        alreadyThumbnail: Boolean = false,
        save: Boolean = true,
    ) {
        val data = if (alreadyThumbnail) imageBytes else downscale(imageBytes) ?: error("이미지를 읽을 수 없음")
        val item = Item(path, folder)
        val k = key(path, folder)
        // Drop a copy saved under the exact name before the folder was known to hold archives
        rawKey(path, folder).takeIf { it != k }?.let { old ->
            File(dir, "$old.jpg").delete()
            index.remove(old)
        }
        File(dir, "$k.jpg").writeBytes(data)
        index[k] = item
        if (save) {
            saveIndex()
            _version.value++
        }
    }

    /** Removes thumbnails (bulk); returns how many were removed. */
    @Synchronized
    fun removeAll(items: List<Item>): Int {
        var n = 0
        items.forEach { item ->
            listOf(key(item.path, item.folder), rawKey(item.path, item.folder)).distinct().forEach { k ->
                if (File(dir, "$k.jpg").delete()) n++
                index.remove(k)
            }
        }
        saveIndex()
        _version.value++
        return n
    }

    /** Saves the index after puts made with save = false (bulk restore). */
    @Synchronized
    fun flush() {
        saveIndex()
        _version.value++
    }

    fun bytes(path: String, folder: Boolean): ByteArray? = get(path, folder)?.readBytes()

    @Synchronized
    fun all(): List<Pair<Item, File>> = index.mapNotNull { (k, item) ->
        File(dir, "$k.jpg").takeIf { it.exists() }?.let { item to it }
    }

    private fun saveIndex() {
        indexFile.writeText(json.encodeToString(indexSerializer, index.toMap()))
    }

    companion object {
        private val memoRegex = Regex("""\s*\[[^\]]*\]\s*""")

        /** "강철 [E북]" → "강철". A name that is only a memo ("[ㄱ]") stays as it is. */
        fun stripMemo(name: String): String =
            name.replace(memoRegex, " ").trim().replace(Regex("""\s+"""), " ").ifEmpty { name }

        fun downscale(bytes: ByteArray): ByteArray? {
            val maxSide = NasRuntime.settings.thumbSize.coerceIn(200, 2000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            return out.toByteArray()
        }
    }
}
