package mihon.feature.nas

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

data class ZipEntryInfo(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
) {
    val baseName: String get() = name.substringAfterLast('/')
    val isDirectory: Boolean get() = name.endsWith("/")
}

/**
 * Custom: reads a zip archive on the NAS using HTTP range requests, so only the central directory
 * and the requested entries are downloaded.
 */
class RemoteZip(
    private val dav: NasWebDav,
    private val path: String,
    val entries: List<ZipEntryInfo>,
) {

    /** Image entries in reading order, excluding the thumbnail file. */
    fun pages(thumbName: String): List<ZipEntryInfo> = entries
        .filter { !it.isDirectory && isImageName(it.name) && !it.baseName.equals(thumbName, ignoreCase = true) }
        .filterNot { it.baseName.startsWith("._") || it.name.startsWith("__MACOSX/") }
        .sortedWith(compareBy(NaturalOrder) { it.name })

    fun thumbEntry(thumbName: String): ZipEntryInfo? =
        entries.firstOrNull { !it.isDirectory && it.baseName.equals(thumbName, ignoreCase = true) }

    fun read(entry: ZipEntryInfo): ByteArray {
        // Local header is 30 bytes + name + extra; the extra field length can differ from the central one
        val guess = 30L + entry.name.toByteArray().size + 1024L
        val end = entry.localHeaderOffset + guess + entry.compressedSize - 1
        var (bytes, _) = dav.getRange(path, "${entry.localHeaderOffset}-$end")
        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != LOCAL_SIG) throw IOException("Bad local header")
        val nameLen = buf.getShort(26).toInt() and 0xFFFF
        val extraLen = buf.getShort(28).toInt() and 0xFFFF
        val dataStart = 30 + nameLen + extraLen
        if (dataStart + entry.compressedSize > bytes.size) {
            val exactEnd = entry.localHeaderOffset + dataStart + entry.compressedSize - 1
            bytes = dav.getRange(path, "${entry.localHeaderOffset}-$exactEnd").first
            buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        val data = bytes.copyOfRange(dataStart, dataStart + entry.compressedSize.toInt())
        return when (entry.method) {
            0 -> data
            8 -> inflate(data, entry.size)
            else -> throw IOException("Unsupported zip method ${entry.method}")
        }
    }

    private fun inflate(data: ByteArray, size: Long): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(if (size in 1..Int.MAX_VALUE) size.toInt() else data.size * 2)
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    companion object {
        private const val LOCAL_SIG = 0x04034b50
        private const val CENTRAL_SIG = 0x02014b50
        private const val EOCD_SIG = 0x06054b50
        private const val ZIP64_LOCATOR_SIG = 0x07064b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val TAIL_SIZE = 65_557L

        private val cache = object : LinkedHashMap<String, RemoteZip>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteZip>?) = size > 8
        }

        fun open(dav: NasWebDav, path: String): RemoteZip {
            synchronized(cache) { cache[path]?.let { return it } }
            val zip = RemoteZip(dav, path, readCentralDirectory(dav, path))
            synchronized(cache) { cache[path] = zip }
            return zip
        }

        private fun readCentralDirectory(dav: NasWebDav, path: String): List<ZipEntryInfo> {
            val (tail, total) = dav.getRange(path, "-$TAIL_SIZE")
            val fileSize = total ?: tail.size.toLong()
            val tailStart = fileSize - tail.size
            val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (buf.getInt(i) == EOCD_SIG) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw IOException("Not a zip file")

            var cdSize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
            var cdOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
                val loc = eocd - 20
                if (loc >= 0 && buf.getInt(loc) == ZIP64_LOCATOR_SIG) {
                    val z64Offset = buf.getLong(loc + 8)
                    val z64 = if (z64Offset >= tailStart) {
                        ByteBuffer.wrap(tail, (z64Offset - tailStart).toInt(), 56).slice()
                            .order(ByteOrder.LITTLE_ENDIAN)
                    } else {
                        ByteBuffer.wrap(dav.getRange(path, "$z64Offset-${z64Offset + 55}").first)
                            .order(ByteOrder.LITTLE_ENDIAN)
                    }
                    if (z64.getInt(0) == ZIP64_EOCD_SIG) {
                        cdSize = z64.getLong(40)
                        cdOffset = z64.getLong(48)
                    }
                }
            }

            val cd: ByteArray = if (cdOffset >= tailStart) {
                tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart + cdSize).toInt())
            } else {
                dav.getRange(path, "$cdOffset-${cdOffset + cdSize - 1}").first
            }
            return parseCentralDirectory(cd)
        }

        private fun parseCentralDirectory(cd: ByteArray): List<ZipEntryInfo> {
            val buf = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
            val result = mutableListOf<ZipEntryInfo>()
            var p = 0
            while (p + 46 <= cd.size && buf.getInt(p) == CENTRAL_SIG) {
                val flags = buf.getShort(p + 8).toInt() and 0xFFFF
                val method = buf.getShort(p + 10).toInt() and 0xFFFF
                var compSize = buf.getInt(p + 20).toLong() and 0xFFFFFFFFL
                var size = buf.getInt(p + 24).toLong() and 0xFFFFFFFFL
                val nameLen = buf.getShort(p + 28).toInt() and 0xFFFF
                val extraLen = buf.getShort(p + 30).toInt() and 0xFFFF
                val commentLen = buf.getShort(p + 32).toInt() and 0xFFFF
                var offset = buf.getInt(p + 42).toLong() and 0xFFFFFFFFL
                val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)

                // Zip64 extra field
                var e = p + 46 + nameLen
                val extraEnd = e + extraLen
                while (e + 4 <= extraEnd) {
                    val id = buf.getShort(e).toInt() and 0xFFFF
                    val len = buf.getShort(e + 2).toInt() and 0xFFFF
                    if (id == 0x0001) {
                        var q = e + 4
                        if (size == 0xFFFFFFFFL) { size = buf.getLong(q); q += 8 }
                        if (compSize == 0xFFFFFFFFL) { compSize = buf.getLong(q); q += 8 }
                        if (offset == 0xFFFFFFFFL) { offset = buf.getLong(q) }
                    }
                    e += 4 + len
                }

                result += ZipEntryInfo(
                    name = decodeName(nameBytes, utf8 = flags and 0x800 != 0),
                    method = method,
                    compressedSize = compSize,
                    size = size,
                    localHeaderOffset = offset,
                )
                p += 46 + nameLen + extraLen + commentLen
            }
            return result
        }

        // Korean archives often use CP949 names without the UTF-8 flag
        private fun decodeName(bytes: ByteArray, utf8: Boolean): String {
            if (utf8) return String(bytes, Charsets.UTF_8)
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                String(bytes, runCatching { Charset.forName("MS949") }.getOrDefault(Charsets.ISO_8859_1))
            }
        }
    }
}
