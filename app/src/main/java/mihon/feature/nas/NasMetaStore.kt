package mihon.feature.nas

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.security.MessageDigest

/**
 * Custom: ComicInfo.xml of NAS archives (made by the PC thumbnail tool), stored on the phone.
 * File name = SHA-1 of "f:<zip path>", same rule as archive thumbnails.
 */
class NasMetaStore(context: Context) {

    data class ComicInfo(
        val title: String? = null,
        val series: String? = null,
        val number: String? = null,
        val volume: String? = null,
        val count: String? = null,
        val summary: String? = null,
        val writer: String? = null,
        val penciller: String? = null,
        val publisher: String? = null,
        val genre: String? = null,
        val tags: String? = null,
        val year: String? = null,
        val pageCount: String? = null,
    ) {
        /** Writer and penciller without duplicates. */
        val authors: String?
            get() = listOfNotNull(writer, penciller)
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
                .ifEmpty { null }

        val genres: List<String>
            get() = listOfNotNull(genre, tags)
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
    }

    private val dir = File(context.filesDir, "nas_meta").apply { mkdirs() }
    private val cache = HashMap<String, ComicInfo?>()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun file(zipPath: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("f:$zipPath".toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".xml")
    }

    fun has(zipPath: String): Boolean = file(zipPath).exists()

    @Synchronized
    fun put(zipPath: String, xml: ByteArray, notify: Boolean = true) {
        file(zipPath).writeBytes(xml)
        cache.remove(zipPath)
        if (notify) _version.value++
    }

    @Synchronized
    fun remove(zipPath: String) {
        file(zipPath).delete()
        cache.remove(zipPath)
    }

    fun notifyChanged() {
        _version.value++
    }

    @Synchronized
    fun get(zipPath: String): ComicInfo? {
        if (cache.containsKey(zipPath)) return cache[zipPath]
        val info = file(zipPath).takeIf { it.exists() }?.let { runCatching { parse(it.readBytes()) }.getOrNull() }
        cache[zipPath] = info
        return info
    }

    /** Series info of a folder = the first archive (natural order) that has metadata. */
    fun seriesFor(zipPaths: List<String>): ComicInfo? =
        zipPaths.sortedWith(NaturalOrder).firstNotNullOfOrNull { get(it) }

    companion object {
        fun parse(bytes: ByteArray): ComicInfo {
            val values = HashMap<String, String>()
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(bytes.inputStream(), null)
            var depth = 0
            var tag: String? = null
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        tag = if (depth == 2) parser.name else null
                    }
                    XmlPullParser.TEXT -> {
                        val t = tag
                        if (t != null && depth == 2) {
                            val text = parser.text.trim()
                            if (text.isNotEmpty()) values[t.lowercase()] = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        depth--
                        tag = null
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return ComicInfo(
                title = values["title"],
                series = values["series"],
                number = values["number"],
                volume = values["volume"],
                count = values["count"],
                summary = values["summary"],
                writer = values["writer"],
                penciller = values["penciller"],
                publisher = values["publisher"],
                genre = values["genre"],
                tags = values["tags"],
                year = values["year"],
                pageCount = values["pagecount"],
            )
        }
    }
}
