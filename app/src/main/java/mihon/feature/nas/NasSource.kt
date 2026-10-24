package mihon.feature.nas

import android.app.Application
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

/**
 * Custom: shared NAS objects (settings, WebDAV client, thumbnails).
 */
object NasRuntime {
    private val app: Application by injectLazy()
    private val network: eu.kanade.tachiyomi.network.NetworkHelper by injectLazy()

    val settings by lazy { NasSettings(app) }
    val dav by lazy { NasWebDav(network.client, settings) }
    val thumbs by lazy { NasThumbStore(app) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Saves an archive thumbnail (if it has none yet) and uses it for the folder when the folder has none.
     */
    fun onArchiveImage(zipPath: String, bytes: ByteArray) {
        if (thumbs.has(zipPath, folder = false)) return
        val small = NasThumbStore.downscale(bytes) ?: return
        thumbs.put(zipPath, folder = false, imageBytes = small, alreadyThumbnail = true)
        val folder = parentPath(zipPath)
        if (!thumbs.has(folder, folder = true)) {
            thumbs.put(folder, folder = true, imageBytes = small, alreadyThumbnail = true)
        }
    }

    /** Makes an archive thumbnail now: thumbnail file inside the zip first, otherwise the first page. */
    fun createArchiveThumbnail(zipPath: String): ByteArray? {
        thumbs.bytes(zipPath, folder = false)?.let { return it }
        val zip = RemoteZip.open(dav, zipPath)
        val entry = zip.thumbEntry(settings.thumbName) ?: zip.pages(settings.thumbName).firstOrNull() ?: return null
        onArchiveImage(zipPath, zip.read(entry))
        return thumbs.bytes(zipPath, folder = false)
    }
}

/**
 * Custom: built-in source that reads zip/cbz archives from a WebDAV share.
 * Manga = folder (url is the folder path), chapter = archive (url is the archive path).
 */
object NasSource : HttpSource() {

    const val SOURCE_ID = 6_284_177_340_118_902_211L
    private const val ZIP_HOST = "nas-zip.invalid"

    override val id: Long = SOURCE_ID
    override val name = "NAS"
    override val lang = "other"
    override val supportsLatest = false

    override val baseUrl: String
        get() = NasRuntime.settings.serverUrl.ifEmpty { "http://localhost" }

    override val client: OkHttpClient by lazy {
        NasRuntime.dav.client.newBuilder()
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.host == ZIP_HOST) serveEntry(chain.request(), url) else chain.proceed(chain.request())
            }
            .build()
    }

    fun chaptersFor(folderPath: String): List<SChapter> {
        val thumbName = NasRuntime.settings.thumbName
        return NasRuntime.dav.list(folderPath)
            .filter { !it.isDirectory && isArchiveName(it.name) && !it.name.equals(thumbName, true) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .mapIndexed { i, entry ->
                SChapter.create().apply {
                    url = entry.path
                    name = entry.name.substringBeforeLast('.')
                    chapter_number = (i + 1).toFloat()
                    date_upload = entry.lastModified
                }
            }
    }

    fun mangaFor(folderPath: String): SManga = SManga.create().apply {
        url = folderPath
        title = fileName(folderPath).ifEmpty { "NAS" }
        initialized = true
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(manga)

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable { chaptersFor(manga.url) }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val zipPath = chapter.url
        val thumbName = NasRuntime.settings.thumbName
        val zip = RemoteZip.open(NasRuntime.dav, zipPath)
        val pages = zip.pages(thumbName)

        // A thumbnail stored inside the archive becomes the archive thumbnail
        if (!NasRuntime.thumbs.has(zipPath, folder = false)) {
            zip.thumbEntry(thumbName)?.let { entry ->
                NasRuntime.scope.launch {
                    runCatching { NasRuntime.onArchiveImage(zipPath, zip.read(entry)) }
                }
            }
        }

        return pages.mapIndexed { i, entry ->
            val url = HttpUrl.Builder()
                .scheme("http")
                .host(ZIP_HOST)
                .addPathSegment("entry")
                .addQueryParameter("z", zipPath)
                .addQueryParameter("e", entry.name)
                .addQueryParameter("p", i.toString())
                .build()
            Page(i, imageUrl = url.toString())
        }
    }

    private fun serveEntry(request: okhttp3.Request, url: HttpUrl): Response {
        val zipPath = url.queryParameter("z").orEmpty()
        val entryName = url.queryParameter("e").orEmpty()
        val pageIndex = url.queryParameter("p")?.toIntOrNull() ?: -1
        val zip = RemoteZip.open(NasRuntime.dav, zipPath)
        val entry = zip.entries.first { it.name == entryName }
        val bytes = zip.read(entry)

        if (pageIndex == 0) {
            NasRuntime.scope.launch {
                try {
                    NasRuntime.onArchiveImage(zipPath, bytes)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "NAS thumbnail failed" }
                }
            }
        }

        val type = when (entryName.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(type.toMediaType()))
            .build()
    }
}
