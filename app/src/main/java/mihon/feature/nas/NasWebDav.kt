package mihon.feature.nas

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.IOException

data class NasEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Custom: minimal WebDAV client (list, ranged read, upload) for the NAS browser.
 */
class NasWebDav(
    private val baseClient: OkHttpClient,
    private val settings: NasSettings,
) {

    private val baseUrl: HttpUrl
        get() = settings.serverUrl.toHttpUrl()

    val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val nasHost = runCatching { baseUrl.host }.getOrNull()
                val user = settings.username
                if (request.url.host == nasHost && user.isNotEmpty() && request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", Credentials.basic(user, settings.password, Charsets.UTF_8))
                            .build(),
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }

    fun url(path: String, isDirectory: Boolean = false): HttpUrl {
        val builder = baseUrl.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        if (isDirectory) builder.addPathSegment("")
        return builder.build()
    }

    /** Lists a folder; a series copied to the phone (and the local tab's top level) is listed from the phone. */
    fun list(path: String, allowLocal: Boolean = true): List<NasEntry> {
        if (allowLocal) NasLocal.list(path)?.let { return it }
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>
        """.trimMargin()
        val request = Request.Builder()
            .url(url(path, isDirectory = true))
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val self = NasSettings.normalizePath(path)
            return parsePropfind(response.body.string())
                .filter { NasSettings.normalizePath(it.path) != self }
        }
    }

    private fun parsePropfind(xml: String): List<NasEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        val result = mutableListOf<NasEntry>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var modified = 0L
        var text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> {
                            href = null
                            isDir = false
                            size = 0L
                            modified = 0L
                        }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> modified = parseHttpDate(text.toString().trim())
                    "response" -> {
                        val h = href
                        if (h != null) {
                            val resolved = baseUrl.resolve(h)
                            if (resolved != null) {
                                val segments = resolved.pathSegments.filter { it.isNotEmpty() }
                                val p = "/" + segments.joinToString("/")
                                result += NasEntry(
                                    path = p,
                                    name = segments.lastOrNull().orEmpty(),
                                    isDirectory = isDir,
                                    size = size,
                                    lastModified = modified,
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseHttpDate(value: String): Long = runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(value)?.time
    }.getOrNull() ?: 0L

    /** GET a whole file; returns null on 404. */
    fun get(path: String): ByteArray? {
        val request = Request.Builder().url(url(path)).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.bytes()
        }
    }

    /**
     * Ranged read. [range] is the value after "bytes=", e.g. "0-99" or "-65557".
     * Returns the bytes and the total size of the file when the server reports it.
     */
    fun getRange(path: String, range: String): Pair<ByteArray, Long?> {
        // Custom: archives copied to the phone are read there (works offline)
        runCatching { NasLocal.readRange(path, range) }.getOrNull()?.let { return it }
        val request = Request.Builder()
            .url(url(path))
            .header("Range", "bytes=$range")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = totalSize(response)
            return response.body.bytes() to total
        }
    }

    private fun totalSize(response: Response): Long? {
        val contentRange = response.header("Content-Range")
        if (contentRange != null) return contentRange.substringAfterLast('/').toLongOrNull()
        return response.body.contentLength().takeIf { response.code == 200 && it >= 0 }
    }

    /** Client for whole-file downloads: no overall time limit (archives can be gigabytes). */
    private val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Streams a whole file into [out]; [onBytes] gets the bytes written so far. */
    fun download(path: String, out: java.io.OutputStream, onBytes: (Long) -> Unit) {
        val request = Request.Builder().url(url(path)).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.byteStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    onBytes(total)
                }
                out.flush()
            }
        }
    }

    fun put(path: String, bytes: ByteArray, contentType: String = "application/octet-stream") {
        val request = Request.Builder()
            .url(url(path))
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    /** Creates the folder and its parents; existing folders are fine. */
    fun mkdirs(path: String, known: MutableSet<String> = mutableSetOf()) {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current += "/$segment"
            if (current in known) continue
            val request = Request.Builder()
                .url(url(current, isDirectory = true))
                .method("MKCOL", null)
                .build()
            client.newCall(request).execute().use { response ->
                // 201 created, 405 already exists
                if (!response.isSuccessful && response.code != 405) {
                    throw IOException("HTTP ${response.code}")
                }
            }
            known += current
        }
    }
}
