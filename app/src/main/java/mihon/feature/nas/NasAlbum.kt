package mihon.feature.nas

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.feature.grid.CoverGridOptionsDialog
import mihon.feature.grid.CoverGridSettings
import mihon.feature.grid.coverGridItems
import mihon.feature.grid.pinchSteps
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Autorenew
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Code
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Keep
import mihon.icons.materialsymbols.rounded.Photo
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.SortByAlpha
import mihon.icons.materialsymbols.roundedfilled.Keep as KeepFilled
import mihon.icons.materialsymbols.roundedfilled.ViewModule
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Custom: album = every folder under one chosen folder that directly holds archives (a "work"),
 * shown as one cover grid like a Komga library, with sections, sorting, tag filters and search.
 */

@Serializable
data class AlbumWork(
    /** Real folder path on the NAS. */
    val path: String,
    /** Natural-first archive in the folder (thumbnail and ComicInfo come from it). */
    val firstZip: String,
    val zipCount: Int,
    /** Newest archive modification time (ms). */
    val modified: Long,
    /** ComicInfo series name, when known. */
    val series: String? = null,
    /** ComicInfo tags of the first archive. */
    val tags: List<String> = emptyList(),
    /** ComicInfo was looked for (found or not), so it is not asked again. */
    val metaChecked: Boolean = false,
) {
    val folderName: String get() = fileName(path)
    val displayName: String get() = series?.takeIf { it.isNotBlank() } ?: NasThumbStore.stripMemo(folderName)
}

@Serializable
private data class AlbumCache(val root: String, val works: List<AlbumWork>)

/** Album settings kept on the phone. */
class AlbumPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("nas_album", Context.MODE_PRIVATE)

    var root: String
        get() = prefs.getString("root", "").orEmpty()
        set(value) = prefs.edit { putString("root", value) }

    var sort: Int
        get() = prefs.getInt("sort", SORT_NAME)
        set(value) = prefs.edit { putInt("sort", value) }

    var ascending: Boolean
        get() = prefs.getBoolean("ascending", true)
        set(value) = prefs.edit { putBoolean("ascending", value) }

    var pinnedTags: List<String>
        get() = prefs.getString("pinned", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString("pinned", value.joinToString("\n")) }

    var selectedTags: Set<String>
        get() = prefs.getString("selected", "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()
        set(value) = prefs.edit { putString("selected", value.joinToString("\n")) }

    companion object {
        const val SORT_NAME = 0
        const val SORT_LATEST = 1
        const val SORT_RANDOM = 2
    }
}

data class AlbumSection(val title: String, val works: List<AlbumWork>)

data class AlbumState(
    val root: String = "",
    val works: List<AlbumWork> = emptyList(),
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    val scannedFolders: Int = 0,
    val metaDone: Int = 0,
    val metaTotal: Int = 0,
    val error: String? = null,
    val sort: Int = AlbumPrefs.SORT_NAME,
    val ascending: Boolean = true,
    val seed: Long = Random.nextLong(),
    val selectedTags: Set<String> = emptySet(),
    val pinnedTags: List<String> = emptyList(),
    val query: String = "",
)

sealed interface AlbumEvent {
    data class OpenManga(val mangaId: Long) : AlbumEvent
    data class Message(val text: String) : AlbumEvent
}

/** Sections (by parent folder) after search, tag filter (all selected tags) and sorting. */
fun albumSections(state: AlbumState): List<AlbumSection> {
    val query = state.query.trim()
    val filtered = state.works.filter { work ->
        (query.isEmpty() || work.displayName.contains(query, true) || work.folderName.contains(query, true)) &&
            state.selectedTags.all { tag -> work.tags.any { it.equals(tag, true) } }
    }
    val root = state.root.trimEnd('/')
    fun sectionOf(work: AlbumWork): String {
        val parent = parentPath(work.path)
        return if (parent.length > root.length && parent.startsWith(root)) parent.substring(root.length).trim('/') else ""
    }
    val random = Random(state.seed)
    val randomKeys = state.works.associate { it.path to random.nextLong() }
    val workOrder: Comparator<AlbumWork> = when (state.sort) {
        AlbumPrefs.SORT_LATEST -> compareBy { it.modified }
        AlbumPrefs.SORT_RANDOM -> compareBy { randomKeys[it.path] ?: 0L }
        else -> compareBy(NaturalOrder) { it.displayName }
    }
    val sections = filtered.groupBy(::sectionOf).map { (title, works) ->
        val sorted = works.sortedWith(workOrder)
        AlbumSection(title, if (state.ascending || state.sort == AlbumPrefs.SORT_RANDOM) sorted else sorted.reversed())
    }
    val sectionOrder: Comparator<AlbumSection> = when (state.sort) {
        AlbumPrefs.SORT_LATEST -> compareBy { s -> s.works.maxOf { it.modified } }
        AlbumPrefs.SORT_RANDOM -> compareBy { s -> s.title.hashCode().toLong() xor state.seed }
        else -> compareBy(NaturalOrder) { it.title }
    }
    val sortedSections = sections.sortedWith(sectionOrder)
    return if (state.ascending || state.sort == AlbumPrefs.SORT_RANDOM) sortedSections else sortedSections.reversed()
}

/** Every tag with the number of works that have it, most used first. */
fun albumTagCounts(works: List<AlbumWork>): List<Pair<String, Int>> {
    val counts = HashMap<String, Int>()
    val names = HashMap<String, String>()
    works.forEach { work ->
        work.tags.map { it.lowercase() }.distinct().forEach { key ->
            counts[key] = (counts[key] ?: 0) + 1
        }
        work.tags.forEach { names.putIfAbsent(it.lowercase(), it) }
    }
    return counts.entries
        .map { (names[it.key] ?: it.key) to it.value }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy(NaturalOrder) { it.first })
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class NasAlbumViewModel(
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val updateManga: eu.kanade.domain.manga.interactor.UpdateManga,
    private val coverCache: eu.kanade.tachiyomi.data.cache.CoverCache,
    private val updateMangaFromRemote: mihon.domain.source.interactor.UpdateMangaFromRemote,
) : ViewModel() {

    private val settings get() = NasRuntime.settings
    private val dav get() = NasRuntime.dav
    private val thumbs get() = NasRuntime.thumbs
    private val prefs = AlbumPrefs(NasRuntime.app)
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(NasRuntime.app.filesDir, "nas_albums/album.json")

    private val _state = MutableStateFlow(
        AlbumState(
            root = prefs.root,
            sort = prefs.sort,
            ascending = prefs.ascending,
            selectedTags = prefs.selectedTags,
            pinnedTags = prefs.pinnedTags,
        ),
    )
    val state: StateFlow<AlbumState> = _state.asStateFlow()

    private val eventChannel = Channel<AlbumEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var scanJob: Job? = null
    private val thumbLimit = Semaphore(4)
    private val thumbRequested = HashSet<String>()

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { readCache() }
            val root = _state.value.root
            if (cached != null && cached.root == root) {
                _state.update { it.copy(works = cached.works, loaded = true) }
                fetchMeta()
            } else {
                _state.update { it.copy(loaded = true) }
                if (root.isNotEmpty() && settings.isConfigured) rescan()
            }
        }
    }

    private fun readCache(): AlbumCache? = runCatching {
        json.decodeFromString(AlbumCache.serializer(), cacheFile.readText())
    }.getOrNull()

    private fun writeCache() {
        val s = _state.value
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(json.encodeToString(AlbumCache.serializer(), AlbumCache(s.root, s.works)))
        }
    }

    fun setRoot(path: String) {
        prefs.root = path
        _state.update { it.copy(root = path, works = emptyList(), selectedTags = emptySet()) }
        prefs.selectedTags = emptySet()
        rescan()
    }

    fun setSort(sort: Int) {
        prefs.sort = sort
        _state.update { it.copy(sort = sort, seed = if (sort == AlbumPrefs.SORT_RANDOM) Random.nextLong() else it.seed) }
    }

    fun toggleAscending() {
        val value = !_state.value.ascending
        prefs.ascending = value
        _state.update { it.copy(ascending = value) }
    }

    fun reshuffle() = _state.update { it.copy(seed = Random.nextLong()) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleTag(tag: String) {
        val current = _state.value.selectedTags
        val next = if (current.any { it.equals(tag, true) }) current.filterNot { it.equals(tag, true) }.toSet() else current + tag
        prefs.selectedTags = next
        _state.update { it.copy(selectedTags = next) }
    }

    fun clearTags() {
        prefs.selectedTags = emptySet()
        _state.update { it.copy(selectedTags = emptySet()) }
    }

    fun togglePin(tag: String) {
        val current = _state.value.pinnedTags
        val next = if (current.any { it.equals(tag, true) }) current.filterNot { it.equals(tag, true) } else current + tag
        prefs.pinnedTags = next
        _state.update { it.copy(pinnedTags = next) }
    }

    /** Walks every folder under the album folder and collects folders that hold archives. */
    fun rescan() {
        val root = _state.value.root
        if (root.isEmpty() || !settings.isConfigured) return
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, scannedFolders = 0, error = null) }
        scanJob = viewModelScope.launch {
            try {
                val found = ConcurrentLinkedQueue<AlbumWork>()
                val counter = AtomicInteger()
                val limit = Semaphore(5)
                val backup = settings.backupPath.trimEnd('/')
                val thumbName = settings.thumbName
                suspend fun walk(dir: String) {
                    val list = limit.withPermit { withContext(Dispatchers.IO) { dav.list(dir) } }
                    _state.update { it.copy(scannedFolders = counter.incrementAndGet()) }
                    val visible = list.filterNot {
                        it.name.startsWith(".") || it.name.startsWith("@") || it.name.startsWith("#") ||
                            it.name.equals(thumbName, true)
                    }
                    val zips = visible.filter { !it.isDirectory && isArchiveName(it.name) }
                    if (zips.isNotEmpty()) {
                        thumbs.markLeaf(dir)
                        val sorted = zips.sortedWith(compareBy(NaturalOrder) { it.name })
                        val old = _state.value.works.firstOrNull { it.path == dir }
                        val firstZip = NasSettings.normalizePath(sorted.first().path)
                        found += AlbumWork(
                            path = dir,
                            firstZip = firstZip,
                            zipCount = zips.size,
                            modified = zips.maxOf { it.lastModified },
                            series = old?.takeIf { it.firstZip == firstZip }?.series,
                            tags = old?.takeIf { it.firstZip == firstZip }?.tags.orEmpty(),
                            metaChecked = old != null && old.firstZip == firstZip && old.metaChecked,
                        )
                    }
                    coroutineScope {
                        visible.filter { it.isDirectory }
                            .map { NasSettings.normalizePath(it.path) }
                            .filterNot { backup.isNotEmpty() && (it == backup || it.startsWith("$backup/")) }
                            .forEach { child -> launch { runCatching { walk(child) } } }
                    }
                }
                walk(NasSettings.normalizePath(root))
                _state.update { it.copy(works = found.toList(), scanning = false) }
                withContext(Dispatchers.IO) { writeCache() }
                fetchMeta()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Album scan failed" }
                _state.update { it.copy(scanning = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** ComicInfo (series name, tags) of each work's first archive: phone, then NAS backup, then the zip. */
    private fun fetchMeta() {
        val todo = _state.value.works.filter { !it.metaChecked }
        if (todo.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(metaDone = 0, metaTotal = todo.size) }
            val limit = Semaphore(4)
            val done = AtomicInteger()
            val results = java.util.concurrent.ConcurrentHashMap<String, AlbumWork>()
            coroutineScope {
                todo.forEach { work ->
                    launch {
                        limit.withPermit {
                            val info = runCatching { loadComicInfo(work.firstZip) }.getOrNull()
                            results[work.path] = work.copy(
                                series = info?.series,
                                tags = info?.tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                                metaChecked = true,
                            )
                        }
                        val n = done.incrementAndGet()
                        if (n % 20 == 0 || n == todo.size) {
                            val snapshot = HashMap(results)
                            _state.update { s ->
                                s.copy(
                                    works = s.works.map { snapshot[it.path] ?: it },
                                    metaDone = n,
                                )
                            }
                        }
                    }
                }
            }
            _state.update { s -> s.copy(works = s.works.map { results[it.path] ?: it }, metaDone = 0, metaTotal = 0) }
            NasRuntime.meta.notifyChanged()
            writeCache()
        }
    }

    private fun loadComicInfo(zip: String): NasMetaStore.ComicInfo? {
        NasRuntime.meta.get(zip)?.let { return it }
        val base = settings.backupPath
        if (base.isNotEmpty()) {
            listOf(thumbs.canon(zip, folder = false), zip).distinct().firstNotNullOfOrNull { p ->
                runCatching { dav.get(base + p + ".ComicInfo.xml") }.getOrNull()
            }?.let {
                NasRuntime.meta.put(zip, it, notify = false)
                return NasRuntime.meta.get(zip)
            }
        }
        // Not in the backup: read ComicInfo.xml straight from the archive (shallowest one)
        val archive = RemoteZip.open(dav, zip)
        val entry = archive.entries
            .filter { !it.isDirectory && it.baseName.equals("ComicInfo.xml", true) }
            .minByOrNull { it.name.count { c -> c == '/' } }
            ?: return null
        NasRuntime.meta.put(zip, archive.read(entry), notify = false)
        return NasRuntime.meta.get(zip)
    }

    /** Folder thumbnail for a tile: phone, NAS backup, thumbnail file in the folder, else the first archive's. */
    fun ensureThumb(work: AlbumWork) {
        if (thumbs.has(work.path, folder = true)) return
        synchronized(thumbRequested) { if (!thumbRequested.add(work.path)) return }
        viewModelScope.launch(Dispatchers.IO) {
            thumbLimit.withPermit {
                runCatching {
                    NasRuntime.restoreOrMake(work.path, folder = true) {
                        runCatching { dav.get(childPath(work.path, settings.thumbName)) }.getOrNull()
                    }
                    if (!thumbs.has(work.path, folder = true)) {
                        NasRuntime.createArchiveThumbnail(work.firstZip)
                    }
                    if (!thumbs.has(work.path, folder = true)) {
                        thumbs.bytes(work.firstZip, folder = false)?.let {
                            thumbs.put(work.path, folder = true, imageBytes = it, alreadyThumbnail = true)
                            NasRuntime.backupOne(work.path, folder = true)
                        }
                    }
                }
            }
        }
    }

    fun open(work: AlbumWork) {
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    networkToLocalManga(NasSource.mangaFor(work.path).toDomainManga(NasSource.SOURCE_ID)).id
                }
                eventChannel.send(AlbumEvent.OpenManga(id))
            } catch (e: Exception) {
                eventChannel.send(AlbumEvent.Message("열기 실패: ${e.message}"))
            }
        }
    }

    fun toggleLibrary(work: AlbumWork) {
        viewModelScope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    val manga = networkToLocalManga(NasSource.mangaFor(work.path).toDomainManga(NasSource.SOURCE_ID))
                    if (manga.favorite) {
                        updateManga.awaitUpdateFavorite(manga.id, false)
                        false
                    } else {
                        syncChaptersWithSource.await(NasSource.chaptersFor(work.path), manga, NasSource)
                        thumbs.get(work.path, folder = true)?.inputStream()?.use { coverCache.setCustomCoverToCache(manga, it) }
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                        updateManga.awaitUpdateFavorite(manga.id, true)
                        updateMangaFromRemote(NasSource, manga, fetchDetails = true)
                        true
                    }
                }
                eventChannel.send(AlbumEvent.Message(if (added) "서재에 추가했어요" else "서재에서 제거했어요"))
            } catch (e: Exception) {
                eventChannel.send(AlbumEvent.Message("실패: ${e.message}"))
            }
        }
    }
}

private val albumAspects = java.util.concurrent.ConcurrentHashMap<String, Float>()

private fun albumThumbAspect(work: AlbumWork): Float? {
    val file = NasRuntime.thumbs.get(work.path, folder = true) ?: return null
    val key = file.path + ":" + file.lastModified()
    albumAspects[key]?.let { return it }
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    return (opts.outWidth.toFloat() / opts.outHeight).also { albumAspects[key] = it }
}

data object AlbumTab : eu.kanade.presentation.util.Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 7u,
            title = "앨범",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Photo),
        )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val context = LocalContext.current
        val viewModel = metroViewModel<NasAlbumViewModel>()
        val state by viewModel.state.collectAsState()
        val thumbVersion by NasRuntime.thumbs.version.collectAsState()
        val profile = CoverGridSettings.album
        val nameMode by profile.nameMode.collectAsState()

        var searchOpen by remember { mutableStateOf(false) }
        var sortMenu by remember { mutableStateOf(false) }
        var tagDialog by remember { mutableStateOf(false) }
        var gridOptions by remember { mutableStateOf(false) }
        var pickRoot by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            viewModel.events.collect { event ->
                when (event) {
                    is AlbumEvent.OpenManga -> navigator.push(MangaScreen(event.mangaId))
                    is AlbumEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val sections = remember(state.works, state.query, state.selectedTags, state.sort, state.ascending, state.seed, state.root) {
            albumSections(state)
        }
        val shownCount = sections.sumOf { it.works.size }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text("앨범", maxLines = 1)
                                if (state.works.isNotEmpty()) {
                                    Text(
                                        if (shownCount == state.works.size) "${state.works.size}작품" else "$shownCount / ${state.works.size}작품",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            mihon.feature.grid.LocalNavBarToggle.current?.let { toggle ->
                                IconButton(onClick = toggle) {
                                    Icon(MaterialSymbols.Rounded.DragHandle, contentDescription = null)
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                searchOpen = !searchOpen
                                if (!searchOpen) viewModel.setQuery("")
                            }) {
                                Icon(MaterialSymbols.Rounded.Search, contentDescription = "검색")
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }) {
                                    Icon(MaterialSymbols.Rounded.SortByAlpha, contentDescription = "정렬")
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    listOf(
                                        AlbumPrefs.SORT_NAME to "이름순",
                                        AlbumPrefs.SORT_LATEST to "최신순",
                                        AlbumPrefs.SORT_RANDOM to "랜덤",
                                    ).forEach { (sort, label) ->
                                        DropdownMenuItem(
                                            text = { Text(if (state.sort == sort) "✓ $label" else label) },
                                            onClick = {
                                                viewModel.setSort(sort)
                                                sortMenu = false
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    if (state.sort == AlbumPrefs.SORT_RANDOM) {
                                        DropdownMenuItem(
                                            text = { Text("다시 섞기") },
                                            leadingIcon = { Icon(MaterialSymbols.Rounded.Autorenew, contentDescription = null) },
                                            onClick = {
                                                viewModel.reshuffle()
                                                sortMenu = false
                                            },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text(if (state.ascending) "오름차순" else "내림차순") },
                                            leadingIcon = {
                                                Icon(
                                                    if (state.ascending) MaterialSymbols.Rounded.ArrowUpward else MaterialSymbols.Rounded.ArrowDownward,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = { viewModel.toggleAscending() },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { tagDialog = true }) {
                                Icon(MaterialSymbols.Rounded.FilterList, contentDescription = "태그")
                            }
                            Box {
                                var more by remember { mutableStateOf(false) }
                                IconButton(onClick = { more = true }) {
                                    Icon(MaterialSymbols.RoundedFilled.ViewModule, contentDescription = null)
                                }
                                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                    DropdownMenuItem(
                                        text = { Text("그리드 설정") },
                                        leadingIcon = { Icon(MaterialSymbols.RoundedFilled.ViewModule, contentDescription = null) },
                                        onClick = {
                                            more = false
                                            gridOptions = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (nameMode) {
                                                    CoverGridSettings.NAME_SHOW -> "이름: 보이기"
                                                    CoverGridSettings.NAME_HIDE -> "이름: 가리기"
                                                    else -> "이름: []만 가리기"
                                                },
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                when (nameMode) {
                                                    CoverGridSettings.NAME_SHOW -> MaterialSymbols.RoundedFilled.Visibility
                                                    CoverGridSettings.NAME_HIDE -> MaterialSymbols.RoundedFilled.VisibilityOff
                                                    else -> MaterialSymbols.Rounded.Code
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = { profile.cycleNameMode() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("다시 스캔") },
                                        leadingIcon = { Icon(MaterialSymbols.Rounded.Refresh, contentDescription = null) },
                                        onClick = {
                                            more = false
                                            viewModel.rescan()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("앨범 폴더 선택") },
                                        leadingIcon = { Icon(MaterialSymbols.Rounded.Folder, contentDescription = null) },
                                        onClick = {
                                            more = false
                                            pickRoot = true
                                        },
                                    )
                                }
                            }
                        },
                    )
                    if (searchOpen) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("제목 검색") },
                            singleLine = true,
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setQuery("") }) {
                                        Icon(MaterialSymbols.Rounded.Close, contentDescription = null)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    AlbumTagBar(
                        pinned = state.pinnedTags,
                        selected = state.selectedTags,
                        onToggle = viewModel::toggleTag,
                        onClear = viewModel::clearTags,
                    )
                    if (state.scanning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (state.metaTotal > 0) {
                        LinearProgressIndicator(
                            progress = { state.metaDone.toFloat() / state.metaTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when {
                    !settings().isConfigured -> CenterMessage("NAS 탭에서 먼저 NAS를 설정하세요")
                    state.root.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "폴더를 고르면 그 아래에서\nzip이 든 폴더를 모두 찾아 작품으로 보여줘요",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { pickRoot = true }) { Text("앨범 폴더 선택") }
                    }
                    state.works.isEmpty() && state.scanning -> CenterMessage("폴더 찾는 중… (${state.scannedFolders}개 확인)")
                    state.error != null && state.works.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("불러오지 못했어요\n${state.error}", textAlign = TextAlign.Center)
                        TextButton(onClick = viewModel::rescan) { Text("다시 시도") }
                    }
                    !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.works.isEmpty() -> CenterMessage("zip이 든 폴더가 없어요")
                    sections.isEmpty() -> CenterMessage("조건에 맞는 작품이 없어요")
                    else -> AlbumGrid(
                        sections = sections,
                        thumbVersion = thumbVersion,
                        nameMode = nameMode,
                        onOpen = viewModel::open,
                        onNeedThumb = viewModel::ensureThumb,
                        onToggleLibrary = viewModel::toggleLibrary,
                        onOpenInNas = { work ->
                            val stack = NasFavorites.stackFor(work.path)
                            NasRuntime.settings.lastStack = stack
                            NasRuntime.pendingJump.value = stack
                            tabNavigator.current = NasTab
                        },
                    )
                }
            }
        }

        if (gridOptions) {
            CoverGridOptionsDialog(profile = profile, onDismiss = { gridOptions = false })
        }
        if (tagDialog) {
            AlbumTagDialog(
                works = state.works,
                selected = state.selectedTags,
                pinned = state.pinnedTags,
                loading = state.metaTotal > 0,
                onToggle = viewModel::toggleTag,
                onTogglePin = viewModel::togglePin,
                onClear = viewModel::clearTags,
                onDismiss = { tagDialog = false },
            )
        }
        if (pickRoot) {
            NasFolderPickerDialog(
                start = state.root.ifEmpty { NasRuntime.settings.rootPath },
                onPick = {
                    pickRoot = false
                    viewModel.setRoot(it)
                },
                onDismiss = { pickRoot = false },
            )
        }
    }

    private fun settings() = NasRuntime.settings
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pinned tags as buttons; selected ones (pinned or not) can be turned off here. */
@Composable
private fun AlbumTagBar(
    pinned: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    val extra = selected.filter { s -> pinned.none { it.equals(s, true) } }
    val chips = pinned + extra
    if (chips.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (selected.isNotEmpty()) {
            item(key = "clear") {
                FilterChip(
                    selected = false,
                    onClick = onClear,
                    label = { Text("해제") },
                    leadingIcon = { Icon(MaterialSymbols.Rounded.Close, contentDescription = null, Modifier.size(16.dp)) },
                )
            }
        }
        items(chips, key = { "tag-$it" }) { tag ->
            FilterChip(
                selected = selected.any { it.equals(tag, true) },
                onClick = { onToggle(tag) },
                label = { Text(tag) },
            )
        }
    }
}

@Composable
private fun AlbumTagDialog(
    works: List<AlbumWork>,
    selected: Set<String>,
    pinned: List<String>,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val counts = remember(works) { albumTagCounts(works) }
    // Counts among works that already have every selected tag (AND)
    val narrowed = remember(works, selected) {
        albumTagCounts(works.filter { w -> selected.all { t -> w.tags.any { it.equals(t, true) } } }).toMap()
    }
    val shown = counts.filter { query.isBlank() || it.first.contains(query.trim(), true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("태그 (모두 포함)") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("태그 검색") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Text(
                        "태그를 읽는 중이에요…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (counts.isEmpty()) {
                    Text(
                        "태그가 없어요 (ComicInfo.xml의 Tags)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.first }) { (tag, total) ->
                        val isSelected = selected.any { it.equals(tag, true) }
                        val isPinned = pinned.any { it.equals(tag, true) }
                        val available = narrowed[tag] ?: narrowed.entries.firstOrNull { it.key.equals(tag, true) }?.value ?: 0
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(tag) },
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = { onToggle(tag) })
                            Text(
                                tag,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (selected.isEmpty() || isSelected) "$total" else "$available/$total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { onTogglePin(tag) }) {
                                Icon(
                                    if (isPinned) MaterialSymbols.RoundedFilled.KeepFilled else MaterialSymbols.Rounded.Keep,
                                    contentDescription = if (isPinned) "고정 해제" else "버튼으로 고정",
                                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            if (selected.isNotEmpty()) TextButton(onClick = onClear) { Text("선택 해제") }
        },
    )
}

@Composable
private fun AlbumGrid(
    sections: List<AlbumSection>,
    thumbVersion: Long,
    nameMode: Int,
    onOpen: (AlbumWork) -> Unit,
    onNeedThumb: (AlbumWork) -> Unit,
    onToggleLibrary: (AlbumWork) -> Unit,
    onOpenInNas: (AlbumWork) -> Unit,
) {
    val profile = CoverGridSettings.album
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val mode by profile.aspectMode.collectAsState()
        val spacing by profile.spacing.collectAsState()
        val rowHeight by profile.rowHeight.collectAsState()
        val savedColumns by profile.columns.collectAsState()
        val columns = CoverGridSettings.columnsOrDefault(savedColumns, maxWidth.value)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pinchSteps(columns to mode) { bigger ->
                    if (mode == CoverGridSettings.MODE_JUSTIFIED) {
                        profile.setRowHeight(CoverGridSettings.stepRowHeight(profile.rowHeight.value, bigger))
                    } else {
                        profile.setColumns(CoverGridSettings.stepColumns(columns, bigger))
                    }
                },
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            sections.forEach { section ->
                item(key = "section-${section.title}") {
                    Text(
                        text = (section.title.ifEmpty { "/" }) + "  ·  ${section.works.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                coverGridItems(
                    items = section.works,
                    key = { it.path },
                    aspectOf = { albumThumbAspect(it) },
                    mode = mode,
                    columns = columns,
                    spacing = spacing.dp,
                    rowHeight = rowHeight.dp,
                    contentWidth = maxWidth,
                    horizontalPadding = 8.dp,
                    groupKey = section.title,
                ) { work, modifier, boxAspect, crop ->
                    AlbumTile(
                        work = work,
                        thumbVersion = thumbVersion,
                        nameMode = nameMode,
                        boxAspect = boxAspect,
                        crop = crop,
                        onClick = { onOpen(work) },
                        onNeedThumb = { onNeedThumb(work) },
                        onToggleLibrary = { onToggleLibrary(work) },
                        onOpenInNas = { onOpenInNas(work) },
                        modifier = modifier,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    work: AlbumWork,
    thumbVersion: Long,
    nameMode: Int,
    boxAspect: Float,
    crop: Boolean,
    onClick: () -> Unit,
    onNeedThumb: () -> Unit,
    onToggleLibrary: () -> Unit,
    onOpenInNas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val thumb: File? = remember(work.path, thumbVersion) { NasRuntime.thumbs.get(work.path, folder = true) }
    LaunchedEffect(work.path, thumb == null) {
        if (thumb == null) onNeedThumb()
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(boxAspect)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumb)
                        .memoryCacheKey("${thumb.path}:${thumb.lastModified()}")
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = work.displayName,
                    contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    MaterialSymbols.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                "${work.zipCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("NAS 탭에서 열기") },
                    onClick = {
                        menu = false
                        onOpenInNas()
                    },
                )
                val favorite = remember(menu) { NasFavorites.contains(work.path) }
                DropdownMenuItem(
                    text = { Text(if (favorite) "즐겨찾기에서 제거" else "즐겨찾기에 추가") },
                    onClick = {
                        menu = false
                        val added = NasFavorites.toggle(work.path)
                        Toast.makeText(
                            context,
                            if (added) "즐겨찾기에 추가했어요" else "즐겨찾기에서 제거했어요",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                DropdownMenuItem(
                    text = { Text("서재에 추가/제거") },
                    onClick = {
                        menu = false
                        onToggleLibrary()
                    },
                )
            }
        }
        val name = when {
            thumb == null -> work.displayName
            nameMode == CoverGridSettings.NAME_HIDE -> null
            nameMode == CoverGridSettings.NAME_NO_BRACKETS -> CoverGridSettings.stripBrackets(work.displayName)
            else -> work.displayName
        }
        if (name != null) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
