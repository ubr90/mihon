package mihon.feature.nas

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom: NAS series copied to a folder on the phone (picked by the user), readable without a connection.
 *
 * The copy keeps the NAS paths as identity: listing a copied series folder and reading its archives go to the
 * phone (see [NasWebDav.list] / [NasWebDav.getRange]), so the NAS tab, library, albums and the reader all use the
 * local files without knowing about them. Each copied folder holds a small info file with its NAS path, so the
 * list can be rebuilt from the folder itself (other folder picked, app data cleared).
 */
object NasLocal {

    /** Virtual path of the local tab's top level (the picked folder). */
    const val ROOT = "/::local"
    private const val INFO_FILE = ".nas_info.json"
    private const val PART = ".part"

    @Serializable
    data class Work(
        /** NAS folder path (exact, as the library uses it). */
        val path: String,
        /** Folder name inside the picked local folder. */
        val dir: String,
        val complete: Boolean = false,
        val addedAt: Long = 0L,
        /** Sub folders of the NAS folder (not copied), still shown when browsing it. */
        val subdirs: List<String> = emptyList(),
    )

    private val app get() = NasRuntime.app
    private val prefs by lazy { app.getSharedPreferences("nas_local", Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Work.serializer())
    private val manifestFile by lazy { File(app.filesDir, "nas_local.json") }

    private val works = ConcurrentHashMap<String, Work>()
    @Volatile private var loaded = false

    private val _version = MutableStateFlow(0L)

    /** Bumped when copies are added, finished or removed (tiles recolor, lists reload). */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Progress of the running copy, shown in the local tab ("제목 · 3/10 · 45%"), null when idle. */
    val progress = MutableStateFlow<String?>(null)

    // ---- settings ----

    /** Picked folder (SAF tree uri), null when not chosen yet. */
    var treeUri: String?
        get() = prefs.getString("tree", null)
        private set(value) = prefs.edit { putString("tree", value) }

    val isConfigured: Boolean get() = treeUri != null

    /** Sets the local folder: keeps its permission and rebuilds the list from the folders found there. */
    fun setFolder(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        treeUri = uri.toString()
        dirCache.clear()
        filesCache.clear()
        rescan()
    }

    /** Readable name of the picked folder for settings ("Comics" of ".../tree/primary:Comics"). */
    fun folderLabel(): String? = treeUri?.let { Uri.decode(it).substringAfterLast(':').substringAfterLast('/').ifEmpty { "/" } }

    // ---- manifest ----

    @Synchronized
    fun load() {
        if (loaded) return
        runCatching {
            if (manifestFile.exists()) {
                json.decodeFromString(listSerializer, manifestFile.readText()).forEach { works[it.path] = it }
            }
        }
        loaded = true
    }

    @Synchronized
    private fun save() {
        runCatching { manifestFile.writeText(json.encodeToString(listSerializer, works.values.toList())) }
        _version.value++
    }

    fun work(folder: String): Work? {
        load()
        return works[NasSettings.normalizePath(folder)]
    }

    /** Copied completely: listings and pages come from the phone. */
    fun isComplete(folder: String): Boolean = work(folder)?.complete == true

    fun allWorks(): List<Work> {
        load()
        return works.values.toList()
    }

    /** Rebuilds the list from the info files in the picked folder (keeps nothing that is not there). */
    fun rescan() {
        load()
        val root = root() ?: return
        val found = mutableMapOf<String, Work>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val info = runCatching {
                dir.findFile(INFO_FILE)?.openInputStream()?.use { json.decodeFromString(Work.serializer(), it.readBytes().decodeToString()) }
            }.getOrNull() ?: return@forEach
            val name = dir.name ?: return@forEach
            found[info.path] = info.copy(dir = name)
            dirCache[name] = dir
        }
        // Copies still in the queue may not have an info file with "complete" yet
        works.values.filter { it.path !in found && root.findFile(it.dir)?.isDirectory == true }.forEach { found[it.path] = it }
        works.clear()
        works.putAll(found)
        filesCache.clear()
        save()
    }

    // ---- files ----

    private val dirCache = ConcurrentHashMap<String, UniFile>()

    /** File listing per local folder name (name -> file), dropped whenever files change. */
    private val filesCache = ConcurrentHashMap<String, Map<String, UniFile>>()

    private fun root(): UniFile? = treeUri?.let { runCatching { UniFile.fromUri(app, Uri.parse(it)) }.getOrNull() }

    private fun dirOf(work: Work, create: Boolean = false): UniFile? {
        dirCache[work.dir]?.takeIf { it.exists() }?.let { return it }
        val root = root() ?: return null
        val dir = root.findFile(work.dir)?.takeIf { it.isDirectory }
            ?: if (create) root.createDirectory(work.dir) else null
        dir?.let { dirCache[work.dir] = it }
        return dir
    }

    private fun filesOf(work: Work): Map<String, UniFile> {
        filesCache[work.dir]?.let { return it }
        val dir = dirOf(work) ?: return emptyMap()
        val map = dir.listFiles().orEmpty()
            .filter { !it.isDirectory }
            .mapNotNull { f -> f.name?.let { it to f } }
            .filter { (name, _) -> !name.endsWith(PART) && name != INFO_FILE }
            .toMap()
        filesCache[work.dir] = map
        return map
    }

    /** The phone copy of a NAS archive, when that series was copied and the file is there. */
    private fun localFile(zipPath: String): UniFile? {
        val work = work(parentPath(zipPath)) ?: return null
        return filesOf(work)[fileName(zipPath)]
    }

    fun hasFile(zipPath: String): Boolean = localFile(zipPath) != null

    /**
     * Listing for [NasWebDav.list]: the top level of the local tab, or a completely copied series folder.
     * Null = not local, ask the NAS.
     */
    fun list(path: String): List<NasEntry>? {
        val p = NasSettings.normalizePath(path)
        if (p == ROOT) {
            return allWorks().map { work ->
                NasEntry(
                    path = work.path,
                    name = fileName(work.path).ifEmpty { work.dir },
                    isDirectory = true,
                    size = 0L,
                    lastModified = work.addedAt,
                )
            }
        }
        val work = work(p)?.takeIf { it.complete } ?: return null
        return work.subdirs.map { name ->
            NasEntry(path = childPath(work.path, name), name = name, isDirectory = true, size = 0L, lastModified = 0L)
        } + filesOf(work).map { (name, file) ->
            NasEntry(
                path = childPath(work.path, name),
                name = name,
                isDirectory = false,
                size = file.length(),
                lastModified = file.lastModified(),
            )
        }
    }

    /**
     * Ranged read for [NasWebDav.getRange] from the phone copy ("a-b" or "-n" like the HTTP Range header).
     * Null = no phone copy of that archive.
     */
    fun readRange(path: String, range: String): Pair<ByteArray, Long?>? {
        val file = localFile(path) ?: return null
        val pfd = app.contentResolver.openFileDescriptor(file.uri, "r") ?: return null
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val channel = input.channel
            val size = channel.size()
            val start: Long
            val end: Long
            if (range.startsWith("-")) {
                val n = range.drop(1).toLong()
                start = (size - n).coerceAtLeast(0L)
                end = size - 1
            } else {
                start = range.substringBefore('-').toLong()
                end = range.substringAfter('-').toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            }
            val length = (end - start + 1).coerceAtLeast(0L).toInt()
            val buffer = ByteBuffer.allocate(length)
            channel.position(start)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            return buffer.array().copyOf(buffer.position()) to size
        }
    }

    // ---- copying ----

    /** Adds a series to the copy queue and starts the background copy. */
    fun enqueue(folder: String) {
        load()
        val path = NasSettings.normalizePath(folder)
        synchronized(this) {
            if (works[path] == null) {
                val used = works.values.map { it.dir }.toSet()
                val base = sanitize(fileName(path)).ifEmpty { "작품" }
                var name = base
                var i = 2
                while (name in used) name = "$base (${i++})"
                works[path] = Work(path, name, complete = false, addedAt = System.currentTimeMillis())
            }
            val queue = queue()
            if (path !in queue) setQueue(queue + path)
        }
        save()
        NasLocalWorker.start(app)
    }

    /** Deletes the phone copy of a series (files and list entry). */
    fun delete(folder: String) {
        load()
        val path = NasSettings.normalizePath(folder)
        val work = synchronized(this) {
            setQueue(queue() - path)
            works.remove(path)
        } ?: return
        runCatching { dirOf(work)?.delete() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Local delete failed" } }
        dirCache.remove(work.dir)
        filesCache.remove(work.dir)
        RemoteZip.forgetFolder(work.path)
        save()
    }

    private fun queue(): List<String> = prefs.getString("queue", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    private fun setQueue(list: List<String>) = prefs.edit { putString("queue", list.joinToString("\n")) }

    @Synchronized
    fun nextQueued(): String? = queue().firstOrNull()

    @Synchronized
    fun dequeue(path: String) = setQueue(queue() - path)

    @Synchronized
    fun clearQueue() = setQueue(emptyList())

    private fun sanitize(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimEnd('.')

    /**
     * Copies every archive of the series that the phone does not have yet (same size = already there),
     * then makes sure thumbnails and ComicInfo are on the phone so the series shows fine offline.
     */
    suspend fun download(path: String, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val work = work(path) ?: return@withContext
        val title = fileName(work.path)
        val dir = dirOf(work, create = true) ?: error("로컬 폴더에 쓸 수 없어요 (폴더를 다시 지정해 주세요)")
        val thumbName = NasRuntime.settings.thumbName
        val listing = NasRuntime.dav.list(work.path, allowLocal = false)
        val subdirs = listing
            .filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("@") && !it.name.startsWith("#") }
            .map { it.name }
        val remote = listing
            .filter { !it.isDirectory && isArchiveName(it.name) && !it.name.equals(thumbName, true) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        if (remote.isEmpty()) error("zip 파일이 없어요")
        writeInfo(dir, work)

        val totalBytes = remote.sumOf { it.size }.coerceAtLeast(1L)
        var doneBytes = 0L
        remote.forEachIndexed { i, entry ->
            ensureActive()
            // Deleted while copying: stop this series quietly
            if (work(path) == null) return@withContext
            val existing = dir.findFile(entry.name)
            if (existing != null && existing.length() == entry.size && entry.size > 0) {
                doneBytes += entry.size
                return@forEachIndexed
            }
            val partName = entry.name + PART
            dir.findFile(partName)?.delete()
            val part = dir.createFile(partName) ?: error("파일을 만들 수 없어요: ${entry.name}")
            try {
                var lastShown = -1
                part.openOutputStream().use { out ->
                    NasRuntime.dav.download(entry.path, out) { bytes ->
                        ensureActive()
                        val percent = ((doneBytes + bytes) * 100 / totalBytes).toInt()
                        if (percent != lastShown) {
                            lastShown = percent
                            onProgress("$title · ${i + 1}/${remote.size} · $percent%")
                        }
                    }
                }
            } catch (e: Throwable) {
                part.delete()
                throw e
            }
            existing?.delete()
            if (!part.renameTo(entry.name)) error("이름 바꾸기 실패: ${entry.name}")
            doneBytes += entry.size
            filesCache.remove(work.dir)
        }

        // Done: from now on the series is listed and read from the phone
        val done = (work(path) ?: return@withContext).copy(complete = true, subdirs = subdirs)
        works[done.path] = done
        writeInfo(dir, done)
        filesCache.remove(done.dir)
        save()

        onProgress("$title · 썸네일 준비 중")
        val zips = remote.map { it.path }
        zips.forEach { zip ->
            runCatching { NasRuntime.createArchiveThumbnail(zip) }
            runCatching { loadComicInfo(zip) }
        }
        if (!NasRuntime.thumbs.has(done.path, folder = true)) {
            runCatching {
                NasRuntime.thumbs.bytes(zips.first(), folder = false)?.let {
                    NasRuntime.thumbs.put(done.path, folder = true, imageBytes = it, alreadyThumbnail = true)
                }
            }
        }
        NasRuntime.meta.notifyChanged()
    }

    private fun writeInfo(dir: UniFile, work: Work) {
        runCatching {
            val file = dir.findFile(INFO_FILE) ?: dir.createFile(INFO_FILE) ?: return
            file.openOutputStream().use { it.write(json.encodeToString(Work.serializer(), work).toByteArray()) }
        }
    }

    /** ComicInfo.xml from the phone store, the NAS backup, or the archive itself. */
    private fun loadComicInfo(zip: String) {
        if (NasRuntime.meta.has(zip)) return
        val base = NasRuntime.settings.backupPath
        if (base.isNotEmpty()) {
            listOf(NasRuntime.thumbs.canon(zip, folder = false), zip).distinct().firstNotNullOfOrNull { p ->
                runCatching { NasRuntime.dav.get(base + p + ".ComicInfo.xml") }.getOrNull()
            }?.let {
                NasRuntime.meta.put(zip, it, notify = false)
                return
            }
        }
        val archive = RemoteZip.open(NasRuntime.dav, zip)
        val entry = archive.entries
            .filter { !it.isDirectory && it.baseName.equals("ComicInfo.xml", true) }
            .minByOrNull { it.name.count { c -> c == '/' } }
            ?: return
        NasRuntime.meta.put(zip, archive.read(entry), notify = false)
    }
}

/**
 * Custom: copies the queued series to the local folder in the background (foreground notification with progress,
 * keeps running when the app is left).
 */
class NasLocalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notification("로컬에 저장 중", null),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private fun notification(text: String, percent: Int?): Notification =
        applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle("로컬에 저장")
            setContentText(text)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOnlyAlertOnce(true)
            setOngoing(true)
            if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true)
            addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "취소",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            setPriority(NotificationCompat.PRIORITY_LOW)
        }.build()

    override suspend fun doWork(): Result {
        setForegroundSafely()
        val failed = mutableListOf<String>()
        try {
            while (true) {
                val path = NasLocal.nextQueued() ?: break
                try {
                    NasLocal.download(path) { text ->
                        NasLocal.progress.value = text
                        val percent = text.substringAfterLast(" · ").removeSuffix("%").toIntOrNull()
                        applicationContext.notify(NOTIFICATION_ID, notification(text, percent))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Local copy failed: $path" }
                    failed += "${fileName(path)}: ${e.message}"
                }
                NasLocal.dequeue(path)
            }
        } catch (e: CancellationException) {
            NasLocal.clearQueue()
            throw e
        } finally {
            NasLocal.progress.value = null
        }
        if (failed.isNotEmpty()) {
            applicationContext.notify(
                NOTIFICATION_ID_RESULT,
                applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_ERROR) {
                    setContentTitle("로컬 저장 실패 ${failed.size}개")
                    setContentText(failed.joinToString("\n"))
                    setStyle(NotificationCompat.BigTextStyle().bigText(failed.joinToString("\n")))
                    setSmallIcon(android.R.drawable.stat_sys_warning)
                }.build(),
            )
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "nas_local_copy"
        private const val NOTIFICATION_ID = -9301
        private const val NOTIFICATION_ID_RESULT = -9302

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<NasLocalWorker>().build()
            // A running copy picks up newly queued series itself; the appended one only catches a series queued
            // just as the running copy was finishing
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
