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

    /** Bumped on every change so the UI reloads tiles. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun key(path: String, folder: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(((if (folder) "d:" else "f:") + path).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun file(path: String, folder: Boolean): File = File(dir, key(path, folder) + ".jpg")

    fun get(path: String, folder: Boolean): File? = file(path, folder).takeIf { it.exists() }

    fun has(path: String, folder: Boolean): Boolean = file(path, folder).exists()

    @Synchronized
    fun put(path: String, folder: Boolean, imageBytes: ByteArray, alreadyThumbnail: Boolean = false) {
        val data = if (alreadyThumbnail) imageBytes else downscale(imageBytes) ?: error("이미지를 읽을 수 없음")
        val k = key(path, folder)
        File(dir, "$k.jpg").writeBytes(data)
        index[k] = Item(path, folder)
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
