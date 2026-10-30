package mihon.feature.nas

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.feature.grid.CoverGridActions
import mihon.feature.grid.CoverGridProfile
import mihon.feature.grid.CoverGridSettings
import mihon.feature.grid.coverGridItems
import mihon.feature.grid.coverGridPinch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Autorenew
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Code
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Keep
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import mihon.icons.materialsymbols.rounded.MoreVert
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
 * Custom: albums. Each album has a name and one or more NAS folders; every folder below them that
 * directly holds archives is a "work". Works are shown as one cover grid (like a Komga library) with
 * sorting, tag/series filters and search. Every album keeps its own settings.
 */

@Serializable
data class AlbumDef(val id: String, val name: String, val roots: List<String>)

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
private data class AlbumCache(val roots: List<String> = emptyList(), val works: List<AlbumWork> = emptyList())

/** Albums and each album's own settings, kept on the phone. */
class AlbumPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("nas_album", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(AlbumDef.serializer())

    var albums: List<AlbumDef>
        get() = runCatching { json.decodeFromString(listSerializer, prefs.getString("albums", "[]").orEmpty()) }
            .getOrDefault(emptyList())
        set(value) = prefs.edit { putString("albums", json.encodeToString(listSerializer, value)) }

    /** Single album of the first version (one folder), moved into the album list once. */
    fun takeLegacyRoot(): String? {
        val root = prefs.getString("root", "").orEmpty()
        if (root.isEmpty()) return null
        prefs.edit { remove("root") }
        return root
    }

    private fun lines(key: String) = prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() }
    private fun setLines(key: String, value: Collection<String>) = prefs.edit { putString(key, value.joinToString("\n")) }

    fun sort(id: String) = prefs.getInt("${id}_sort", SORT_NAME)
    fun setSort(id: String, v: Int) = prefs.edit { putInt("${id}_sort", v) }
    fun ascending(id: String) = prefs.getBoolean("${id}_asc", true)
    fun setAscending(id: String, v: Boolean) = prefs.edit { putBoolean("${id}_asc", v) }
    fun pinnedTags(id: String) = lines("${id}_pin_tags")
    fun setPinnedTags(id: String, v: List<String>) = setLines("${id}_pin_tags", v)
    fun pinnedSeries(id: String) = lines("${id}_pin_series")
    fun setPinnedSeries(id: String, v: List<String>) = setLines("${id}_pin_series", v)
    fun selectedTags(id: String) = lines("${id}_sel_tags").toSet()
    fun setSelectedTags(id: String, v: Set<String>) = setLines("${id}_sel_tags", v)
    fun selectedSeries(id: String) = lines("${id}_sel_series").toSet()
    fun setSelectedSeries(id: String, v: Set<String>) = setLines("${id}_sel_series", v)

    fun removeAlbum(id: String) {
        prefs.edit {
            listOf("sort", "asc", "pin_tags", "pin_series", "sel_tags", "sel_series").forEach { remove("${id}_$it") }
        }
    }

    companion object {
        const val SORT_NAME = 0
        const val SORT_LATEST = 1
        const val SORT_RANDOM = 2
    }
}

data class AlbumSummary(val count: Int, val cover: String?)

data class AlbumState(
    val albums: List<AlbumDef> = emptyList(),
    val summaries: Map<String, AlbumSummary> = emptyMap(),
    /** Album shown now, or null for the album list. */
    val open: AlbumDef? = null,
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
    val selectedSeries: Set<String> = emptySet(),
    val pinnedTags: List<String> = emptyList(),
    val pinnedSeries: List<String> = emptyList(),
    val query: String = "",
)

sealed interface AlbumEvent {
    data class OpenManga(val mangaId: Long) : AlbumEvent
    data class Message(val text: String) : AlbumEvent
}

private fun List<String>.hasIgnoreCase(value: String) = any { it.equals(value, true) }
private fun Collection<String>.hasIgnoreCase(value: String) = any { it.equals(value, true) }

/** Works after search, tag filter (all selected tags), series filter (any selected series) and sorting. */
fun albumWorks(state: AlbumState): List<AlbumWork> {
    val query = state.query.trim()
    val filtered = state.works.filter { work ->
        (query.isEmpty() || work.displayName.contains(query, true) || work.folderName.contains(query, true)) &&
            state.selectedTags.all { tag -> work.tags.hasIgnoreCase(tag) } &&
            (state.selectedSeries.isEmpty() || work.series?.let { state.selectedSeries.hasIgnoreCase(it) } == true)
    }
    if (state.sort == AlbumPrefs.SORT_RANDOM) {
        val random = Random(state.seed)
        val keys = state.works.associate { it.path to random.nextLong() }
        return filtered.sortedBy { keys[it.path] ?: 0L }
    }
    val order: Comparator<AlbumWork> = when (state.sort) {
        AlbumPrefs.SORT_LATEST -> compareBy { it.modified }
        else -> compareBy(NaturalOrder) { it.displayName }
    }
    val sorted = filtered.sortedWith(order)
    return if (state.ascending) sorted else sorted.reversed()
}

/** Values with the number of works having them, most used first. */
private fun countValues(works: List<AlbumWork>, valuesOf: (AlbumWork) -> List<String>): List<Pair<String, Int>> {
    val counts = HashMap<String, Int>()
    val names = HashMap<String, String>()
    works.forEach { work ->
        val values = valuesOf(work)
        values.map { it.lowercase() }.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        values.forEach { names.putIfAbsent(it.lowercase(), it) }
    }
    return counts.entries
        .map { (names[it.key] ?: it.key) to it.value }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy(NaturalOrder) { it.first })
}

fun albumTagCounts(works: List<AlbumWork>) = countValues(works) { it.tags }
fun albumSeriesCounts(works: List<AlbumWork>) = countValues(works) { listOfNotNull(it.series?.takeIf { s -> s.isNotBlank() }) }

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
    private val cacheDir = File(NasRuntime.app.filesDir, "nas_albums")

    private val _state = MutableStateFlow(AlbumState())
    val state: StateFlow<AlbumState> = _state.asStateFlow()

    private val eventChannel = Channel<AlbumEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var scanJob: Job? = null
    private var metaJob: Job? = null
    private val thumbLimit = Semaphore(4)
    private val thumbRequested = HashSet<String>()

    init {
        // First version had one album (one folder, cache "album.json"): keep it as an album named "앨범"
        prefs.takeLegacyRoot()?.let { root ->
            val def = AlbumDef(newId(), "앨범", listOf(root))
            prefs.albums = prefs.albums + def
            File(cacheDir, "album.json").takeIf { it.exists() }?.renameTo(cacheFile(def.id))
        }
        _state.update { it.copy(albums = prefs.albums) }
        loadSummaries()
    }

    override fun onCleared() {
        NasRuntime.albumOpen.value = false
        super.onCleared()
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + Random.nextInt(1000)

    private fun cacheFile(id: String) = File(cacheDir, "$id.json")

    private fun readCache(id: String): AlbumCache? = runCatching {
        json.decodeFromString(AlbumCache.serializer(), cacheFile(id).readText())
    }.getOrNull()

    private fun writeCache(def: AlbumDef, works: List<AlbumWork>) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(def.id).writeText(json.encodeToString(AlbumCache.serializer(), AlbumCache(def.roots, works)))
        }
    }

    private fun loadSummaries() {
        viewModelScope.launch(Dispatchers.IO) {
            val map = _state.value.albums.associate { def ->
                val works = readCache(def.id)?.works.orEmpty()
                def.id to AlbumSummary(works.size, works.minWithOrNull(compareBy(NaturalOrder) { it.displayName })?.path)
            }
            _state.update { it.copy(summaries = map) }
        }
    }

    // ---- album list ----

    fun saveAlbum(id: String?, name: String, roots: List<String>) {
        val clean = name.trim().ifEmpty { "앨범" }
        val list = prefs.albums
        val def: AlbumDef
        val updated = if (id == null) {
            def = AlbumDef(newId(), clean, roots)
            list + def
        } else {
            val old = list.firstOrNull { it.id == id } ?: return
            def = old.copy(name = clean, roots = roots)
            list.map { if (it.id == id) def else it }
        }
        prefs.albums = updated
        _state.update { it.copy(albums = updated, open = if (it.open?.id == def.id || id == null) def else it.open) }
        if (id == null) {
            openAlbum(def)
        } else if (_state.value.open?.id == def.id && readCache(def.id)?.roots != roots) {
            rescan()
        }
    }

    fun deleteAlbum(id: String) {
        prefs.albums = prefs.albums.filterNot { it.id == id }
        prefs.removeAlbum(id)
        CoverGridSettings.albumProfile(id).clear()
        cacheFile(id).delete()
        if (_state.value.open?.id == id) closeAlbum()
        _state.update { it.copy(albums = prefs.albums) }
        loadSummaries()
    }

    fun openAlbum(def: AlbumDef) {
        scanJob?.cancel()
        metaJob?.cancel()
        NasRuntime.albumOpen.value = true
        _state.update {
            it.copy(
                open = def,
                works = emptyList(),
                loaded = false,
                scanning = false,
                error = null,
                query = "",
                metaDone = 0,
                metaTotal = 0,
                sort = prefs.sort(def.id),
                ascending = prefs.ascending(def.id),
                seed = Random.nextLong(),
                selectedTags = prefs.selectedTags(def.id),
                selectedSeries = prefs.selectedSeries(def.id),
                pinnedTags = prefs.pinnedTags(def.id),
                pinnedSeries = prefs.pinnedSeries(def.id),
            )
        }
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { readCache(def.id) }
            if (_state.value.open?.id != def.id) return@launch
            if (cached != null && cached.roots == def.roots) {
                _state.update { it.copy(works = cached.works, loaded = true) }
                fetchMeta()
            } else {
                _state.update { it.copy(loaded = true) }
                rescan()
            }
        }
    }

    fun closeAlbum() {
        scanJob?.cancel()
        metaJob?.cancel()
        NasRuntime.albumOpen.value = false
        _state.update { it.copy(open = null, works = emptyList(), scanning = false, metaTotal = 0) }
        loadSummaries()
    }

    // ---- album settings ----

    private inline fun withOpen(block: (AlbumDef) -> Unit) {
        _state.value.open?.let(block)
    }

    fun setSort(sort: Int) = withOpen { def ->
        prefs.setSort(def.id, sort)
        _state.update { it.copy(sort = sort, seed = if (sort == AlbumPrefs.SORT_RANDOM) Random.nextLong() else it.seed) }
    }

    fun toggleAscending() = withOpen { def ->
        val value = !_state.value.ascending
        prefs.setAscending(def.id, value)
        _state.update { it.copy(ascending = value) }
    }

    fun reshuffle() = _state.update { it.copy(seed = Random.nextLong()) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    private fun toggled(set: Set<String>, value: String) =
        if (set.hasIgnoreCase(value)) set.filterNot { it.equals(value, true) }.toSet() else set + value

    private fun toggled(list: List<String>, value: String) =
        if (list.hasIgnoreCase(value)) list.filterNot { it.equals(value, true) } else list + value

    fun toggleTag(tag: String) = withOpen { def ->
        val next = toggled(_state.value.selectedTags, tag)
        prefs.setSelectedTags(def.id, next)
        _state.update { it.copy(selectedTags = next) }
    }

    fun toggleSeries(series: String) = withOpen { def ->
        val next = toggled(_state.value.selectedSeries, series)
        prefs.setSelectedSeries(def.id, next)
        _state.update { it.copy(selectedSeries = next) }
    }

    fun clearFilters() = withOpen { def ->
        prefs.setSelectedTags(def.id, emptySet())
        prefs.setSelectedSeries(def.id, emptySet())
        _state.update { it.copy(selectedTags = emptySet(), selectedSeries = emptySet()) }
    }

    fun togglePinTag(tag: String) = withOpen { def ->
        val next = toggled(_state.value.pinnedTags, tag)
        prefs.setPinnedTags(def.id, next)
        _state.update { it.copy(pinnedTags = next) }
    }

    fun togglePinSeries(series: String) = withOpen { def ->
        val next = toggled(_state.value.pinnedSeries, series)
        prefs.setPinnedSeries(def.id, next)
        _state.update { it.copy(pinnedSeries = next) }
    }

    // ---- scanning ----

    /** Walks every folder under the album's folders and collects folders that hold archives. */
    fun rescan() {
        val def = _state.value.open ?: return
        if (def.roots.isEmpty() || !settings.isConfigured) return
        scanJob?.cancel()
        metaJob?.cancel()
        _state.update { it.copy(scanning = true, scannedFolders = 0, error = null, metaTotal = 0) }
        scanJob = viewModelScope.launch {
            try {
                val found = ConcurrentLinkedQueue<AlbumWork>()
                val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val counter = AtomicInteger()
                val limit = Semaphore(5)
                val backup = settings.backupPath.trimEnd('/')
                val thumbName = settings.thumbName
                val previous = _state.value.works.associateBy { it.path }
                suspend fun walk(dir: String) {
                    if (!seen.add(dir)) return
                    val list = limit.withPermit { withContext(Dispatchers.IO) { dav.list(dir) } }
                    _state.update { it.copy(scannedFolders = counter.incrementAndGet()) }
                    val visible = list.filterNot {
                        it.name.startsWith(".") || it.name.startsWith("@") || it.name.startsWith("#") ||
                            it.name.equals(thumbName, true)
                    }
                    val zips = visible.filter { !it.isDirectory && isArchiveName(it.name) }
                    if (zips.isNotEmpty()) {
                        thumbs.markLeaf(dir)
                        val firstZip = NasSettings.normalizePath(zips.sortedWith(compareBy(NaturalOrder) { it.name }).first().path)
                        val old = previous[dir]?.takeIf { it.firstZip == firstZip }
                        found += AlbumWork(
                            path = dir,
                            firstZip = firstZip,
                            zipCount = zips.size,
                            modified = zips.maxOf { it.lastModified },
                            series = old?.series,
                            tags = old?.tags.orEmpty(),
                            metaChecked = old?.metaChecked == true,
                        )
                    }
                    coroutineScope {
                        visible.filter { it.isDirectory }
                            .map { NasSettings.normalizePath(it.path) }
                            .filter { it != dir }
                            .filterNot { backup.isNotEmpty() && (it == backup || it.startsWith("$backup/")) }
                            .forEach { child -> launch { runCatching { walk(child) } } }
                    }
                }
                coroutineScope {
                    def.roots.forEach { root -> launch { walk(NasSettings.normalizePath(root)) } }
                }
                val works = found.toList()
                if (_state.value.open?.id != def.id) return@launch
                _state.update { it.copy(works = works, scanning = false) }
                withContext(Dispatchers.IO) { writeCache(def, works) }
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
        val def = _state.value.open ?: return
        val todo = _state.value.works.filter { !it.metaChecked }
        if (todo.isEmpty()) return
        metaJob?.cancel()
        metaJob = viewModelScope.launch(Dispatchers.IO) {
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
                                series = info?.series?.trim()?.ifEmpty { null },
                                tags = info?.tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                                metaChecked = true,
                            )
                        }
                        val n = done.incrementAndGet()
                        if (n % 20 == 0 || n == todo.size) {
                            val snapshot = HashMap(results)
                            _state.update { s ->
                                if (s.open?.id != def.id) s else s.copy(works = s.works.map { snapshot[it.path] ?: it }, metaDone = n)
                            }
                        }
                    }
                }
            }
            NasRuntime.meta.notifyChanged()
            if (_state.value.open?.id != def.id) return@launch
            _state.update { s -> s.copy(works = s.works.map { results[it.path] ?: it }, metaDone = 0, metaTotal = 0) }
            writeCache(def, _state.value.works)
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
    fun ensureThumb(path: String, firstZip: String?) {
        if (thumbs.has(path, folder = true)) return
        synchronized(thumbRequested) { if (!thumbRequested.add(path)) return }
        viewModelScope.launch(Dispatchers.IO) {
            thumbLimit.withPermit {
                runCatching {
                    NasRuntime.restoreOrMake(path, folder = true) {
                        runCatching { dav.get(childPath(path, settings.thumbName)) }.getOrNull()
                    }
                    if (firstZip != null && !thumbs.has(path, folder = true)) {
                        NasRuntime.createArchiveThumbnail(firstZip)
                        if (!thumbs.has(path, folder = true)) {
                            thumbs.bytes(firstZip, folder = false)?.let {
                                thumbs.put(path, folder = true, imageBytes = it, alreadyThumbnail = true)
                                NasRuntime.backupOne(path, folder = true)
                            }
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

/** Album being added (id null) or edited. */
private data class AlbumEdit(val id: String?, val name: String, val roots: List<String>)

data object AlbumTab : eu.kanade.presentation.util.Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 7u,
            title = "앨범",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Photo),
        )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val context = LocalContext.current
        val viewModel = metroViewModel<NasAlbumViewModel>()
        val state by viewModel.state.collectAsState()
        var edit by remember { mutableStateOf<AlbumEdit?>(null) }

        LaunchedEffect(Unit) {
            viewModel.events.collect { event ->
                when (event) {
                    is AlbumEvent.OpenManga -> navigator.push(
                        MangaScreen(
                            event.mangaId,
                            gridProfileId = viewModel.state.value.open?.let { CoverGridSettings.albumProfile(it.id).id },
                        ),
                    )
                    is AlbumEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                }
            }
        }
        LaunchedEffect(state.open) { NasRuntime.albumOpen.value = state.open != null }

        val open = state.open
        if (open == null) {
            AlbumListScreen(
                state = state,
                onOpen = viewModel::openAlbum,
                onAdd = { edit = AlbumEdit(null, "", emptyList()) },
                onEdit = { edit = AlbumEdit(it.id, it.name, it.roots) },
            )
        } else {
            BackHandler { viewModel.closeAlbum() }
            key(open.id) {
                AlbumScreen(
                    album = open,
                    state = state,
                    viewModel = viewModel,
                    onEdit = { edit = AlbumEdit(open.id, open.name, open.roots) },
                    onOpenInNas = { work ->
                        val stack = NasFavorites.stackFor(work.path)
                        NasRuntime.settings.lastStack = stack
                        NasRuntime.pendingJump.value = stack
                        tabNavigator.current = NasTab
                    },
                )
            }
        }

        edit?.let { current ->
            AlbumEditDialog(
                edit = current,
                onSave = { name, roots ->
                    edit = null
                    viewModel.saveAlbum(current.id, name, roots)
                },
                onDelete = current.id?.let { id ->
                    {
                        edit = null
                        viewModel.deleteAlbum(id)
                    }
                },
                onDismiss = { edit = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AlbumListScreen(
    state: AlbumState,
    onOpen: (AlbumDef) -> Unit,
    onAdd: () -> Unit,
    onEdit: (AlbumDef) -> Unit,
) {
    val context = LocalContext.current
    val thumbVersion by NasRuntime.thumbs.version.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("앨범") },
                navigationIcon = { NavToggleButton() },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(MaterialSymbols.Rounded.Add, contentDescription = "앨범 추가")
                    }
                },
            )
        },
    ) { padding ->
        if (!NasRuntime.settings.isConfigured) {
            Box(Modifier.padding(padding)) { CenterMessage("NAS 탭에서 먼저 NAS를 설정하세요") }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(state.albums, key = { it.id }) { album ->
                val summary = state.summaries[album.id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(album) }, onLongClick = { onEdit(album) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val cover = remember(summary?.cover, thumbVersion) {
                        summary?.cover?.let { NasRuntime.thumbs.get(it, folder = true) }
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp, 76.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cover != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(cover)
                                    .memoryCacheKey("${cover.path}:${cover.lastModified()}")
                                    .diskCachePolicy(CachePolicy.DISABLED)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(MaterialSymbols.Rounded.Photo, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                summary?.let { "${it.count}작품" },
                                "폴더 ${album.roots.size}개",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            album.roots.joinToString(", ") { fileName(it).ifEmpty { "/" } },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onEdit(album) }) {
                        Icon(MaterialSymbols.Rounded.Edit, contentDescription = "앨범 설정")
                    }
                }
            }
            item(key = "add") {
                OutlinedButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(MaterialSymbols.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("앨범 추가하기")
                }
            }
        }
    }
}

@Composable
private fun NavToggleButton() {
    mihon.feature.grid.LocalNavBarToggle.current?.let { toggle ->
        IconButton(onClick = toggle) {
            Icon(MaterialSymbols.Rounded.DragHandle, contentDescription = null)
        }
    }
}

@Composable
private fun AlbumEditDialog(
    edit: AlbumEdit,
    onSave: (String, List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(edit.name) }
    var roots by remember { mutableStateOf(edit.roots) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.id == null) "앨범 추가" else "앨범 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("앨범 이름") },
                    placeholder = { Text("예: 만화, 웹툰") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("폴더", style = MaterialTheme.typography.labelLarge)
                if (roots.isEmpty()) {
                    Text(
                        "고른 폴더 아래에서 zip이 든 폴더를 모두 찾아 작품으로 보여줘요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(modifier = Modifier.heightIn(max = 160.dp)) {
                        LazyColumn {
                            items(roots, key = { it }) { root ->
                                Text(root, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(MaterialSymbols.Rounded.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (roots.isEmpty()) "폴더 선택" else "폴더 바꾸기 (${roots.size}개)")
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("앨범 삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = roots.isNotEmpty(), onClick = { onSave(name, roots) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    if (picking) {
        NasFolderCheckDialog(
            initial = roots.toSet(),
            onConfirm = {
                roots = it.sortedWith(NaturalOrder)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(name.ifBlank { "앨범" }) },
            text = { Text("앨범을 삭제할까요? (NAS 파일과 썸네일은 그대로예요)") },
            confirmButton = { TextButton(onClick = onDelete) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/**
 * NAS folder tree with checkboxes (folders load when opened). A checked folder includes
 * everything below it.
 */
@Composable
fun NasFolderCheckDialog(
    initial: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val root = NasRuntime.settings.rootPath
    val backup = NasRuntime.settings.backupPath.trimEnd('/')
    var checked by remember { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(setOf(root)) }
    val children = remember { mutableStateMapOf<String, List<String>>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }
    val failed = remember { mutableStateMapOf<String, String>() }

    fun isUnder(path: String, parent: String) = parent == "/" || path.startsWith("$parent/")
    fun coveredByParent(path: String) = checked.any { it != path && isUnder(path, it) }

    @Composable
    fun Load(path: String) {
        LaunchedEffect(path) {
            if (children.containsKey(path) || loading[path] == true) return@LaunchedEffect
            loading[path] = true
            try {
                val list = withContext(Dispatchers.IO) { NasRuntime.dav.list(path) }
                children[path] = list
                    .filter { it.isDirectory }
                    .map { NasSettings.normalizePath(it.path) }
                    .filter { it != path }
                    .filterNot { fileName(it).startsWith(".") || fileName(it).startsWith("@") || fileName(it).startsWith("#") }
                    .filterNot { backup.isNotEmpty() && it == backup }
                    .sortedWith(compareBy(NaturalOrder) { fileName(it) })
                failed.remove(path)
            } catch (e: Exception) {
                failed[path] = e.message ?: e.toString()
            } finally {
                loading[path] = false
            }
        }
    }

    // Rows of the visible tree
    val rows = mutableListOf<Pair<String, Int>>()
    fun walk(path: String, depth: Int) {
        children[path]?.forEach { child ->
            rows += child to depth
            if (child in expanded) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    expanded.forEach { path -> key(path) { Load(path) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("폴더 선택") },
        text = {
            Column {
                Text(
                    "체크한 폴더 아래 전체가 앨범에 들어가요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failed[root]?.let { Text("불러오지 못했어요: $it", color = MaterialTheme.colorScheme.error) }
                if (loading[root] == true && rows.isEmpty()) {
                    CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp).padding(top = 8.dp)) {
                    items(rows, key = { it.first }) { (path, depth) ->
                        val isOpen = path in expanded
                        val covered = coveredByParent(path)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (depth * 16).dp),
                        ) {
                            IconButton(
                                onClick = { expanded = if (isOpen) expanded - path else expanded + path },
                                modifier = Modifier.size(32.dp),
                            ) {
                                if (loading[path] == true) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else if (children[path]?.isEmpty() != true) {
                                    Icon(
                                        if (isOpen) MaterialSymbols.Rounded.KeyboardArrowDown else MaterialSymbols.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                    )
                                }
                            }
                            Checkbox(
                                checked = covered || path in checked,
                                enabled = !covered,
                                onCheckedChange = { on ->
                                    checked = if (on) {
                                        checked.filterNot { isUnder(it, path) }.toSet() + path
                                    } else {
                                        checked - path
                                    }
                                },
                            )
                            Text(
                                fileName(path),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { expanded = if (isOpen) expanded - path else expanded + path },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = checked.isNotEmpty(), onClick = { onConfirm(checked) }) { Text("선택 (${checked.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumScreen(
    album: AlbumDef,
    state: AlbumState,
    viewModel: NasAlbumViewModel,
    onEdit: () -> Unit,
    onOpenInNas: (AlbumWork) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val thumbVersion by NasRuntime.thumbs.version.collectAsState()
    val profile = remember(album.id) { CoverGridSettings.albumProfile(album.id) }
    val nameMode by profile.nameMode.collectAsState()

    var searchOpen by remember { mutableStateOf(false) }
    var filterDialog by remember { mutableStateOf(false) }

    val works = remember(state.works, state.query, state.selectedTags, state.selectedSeries, state.sort, state.ascending, state.seed) {
        albumWorks(state)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.works.isNotEmpty()) {
                                Text(
                                    if (works.size == state.works.size) "${state.works.size}작품" else "${works.size} / ${state.works.size}작품",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        Row {
                            NavToggleButton()
                            IconButton(onClick = viewModel::closeAlbum) {
                                Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = null)
                            }
                        }
                    },
                    actions = {
                        CoverGridActions(profile = profile)
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) viewModel.setQuery("")
                        }) {
                            Icon(MaterialSymbols.Rounded.Search, contentDescription = "검색")
                        }
                        IconButton(onClick = { filterDialog = true }) {
                            Icon(MaterialSymbols.Rounded.FilterList, contentDescription = "필터")
                        }
                        Box {
                            var more by remember { mutableStateOf(false) }
                            IconButton(onClick = { more = true }) {
                                Icon(MaterialSymbols.Rounded.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                listOf(
                                    AlbumPrefs.SORT_NAME to "이름순",
                                    AlbumPrefs.SORT_LATEST to "최신순",
                                    AlbumPrefs.SORT_RANDOM to "랜덤",
                                ).forEach { (sort, label) ->
                                    DropdownMenuItem(
                                        text = { Text(if (state.sort == sort) "✓ $label" else label) },
                                        leadingIcon = { Icon(MaterialSymbols.Rounded.SortByAlpha, contentDescription = null) },
                                        onClick = {
                                            viewModel.setSort(sort)
                                            more = false
                                        },
                                    )
                                }
                                if (state.sort == AlbumPrefs.SORT_RANDOM) {
                                    DropdownMenuItem(
                                        text = { Text("다시 섞기") },
                                        leadingIcon = { Icon(MaterialSymbols.Rounded.Autorenew, contentDescription = null) },
                                        onClick = {
                                            viewModel.reshuffle()
                                            more = false
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
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("다시 스캔") },
                                    leadingIcon = { Icon(MaterialSymbols.Rounded.Refresh, contentDescription = null) },
                                    onClick = {
                                        more = false
                                        viewModel.rescan()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("앨범 설정") },
                                    leadingIcon = { Icon(MaterialSymbols.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        more = false
                                        onEdit()
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
                AlbumChipBar(
                    state = state,
                    onToggleTag = viewModel::toggleTag,
                    onToggleSeries = viewModel::toggleSeries,
                    onClear = viewModel::clearFilters,
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
                state.works.isEmpty() && state.scanning -> CenterMessage("폴더 찾는 중… (${state.scannedFolders}개 확인)")
                state.error != null && state.works.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("불러오지 못했어요\n${state.error}", textAlign = TextAlign.Center)
                    TextButton(onClick = viewModel::rescan) { Text("다시 시도") }
                }
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.works.isEmpty() -> CenterMessage("zip이 든 폴더가 없어요")
                works.isEmpty() -> CenterMessage("조건에 맞는 작품이 없어요")
                else -> AlbumGrid(
                    albumId = album.id,
                    profile = profile,
                    works = works,
                    thumbVersion = thumbVersion,
                    nameMode = nameMode,
                    onOpen = { work ->
                        // Custom: a work opens in the NAS screen (same screen everywhere)
                        navigator.push(NasBrowserScreen(startPath = work.path, gridProfileId = profile.id))
                    },
                    onNeedThumb = { viewModel.ensureThumb(it.path, it.firstZip) },
                    onToggleLibrary = viewModel::toggleLibrary,
                    onOpenInNas = onOpenInNas,
                )
            }
        }
    }

    if (filterDialog) {
        AlbumFilterDialog(
            state = state,
            onToggleTag = viewModel::toggleTag,
            onToggleSeries = viewModel::toggleSeries,
            onPinTag = viewModel::togglePinTag,
            onPinSeries = viewModel::togglePinSeries,
            onClear = viewModel::clearFilters,
            onDismiss = { filterDialog = false },
        )
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pinned tags/series as buttons; selected ones that are not pinned show here too so they can be turned off. */
@Composable
private fun AlbumChipBar(
    state: AlbumState,
    onToggleTag: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onClear: () -> Unit,
) {
    val tags = state.pinnedTags + state.selectedTags.filterNot { state.pinnedTags.hasIgnoreCase(it) }
    val series = state.pinnedSeries + state.selectedSeries.filterNot { state.pinnedSeries.hasIgnoreCase(it) }
    if (tags.isEmpty() && series.isEmpty()) return
    val anySelected = state.selectedTags.isNotEmpty() || state.selectedSeries.isNotEmpty()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (anySelected) {
            item(key = "clear") {
                FilterChip(
                    selected = false,
                    onClick = onClear,
                    label = { Text("해제") },
                    leadingIcon = { Icon(MaterialSymbols.Rounded.Close, contentDescription = null, Modifier.size(16.dp)) },
                )
            }
        }
        items(series, key = { "series-$it" }) { name ->
            FilterChip(
                selected = state.selectedSeries.hasIgnoreCase(name),
                onClick = { onToggleSeries(name) },
                label = { Text(name) },
                leadingIcon = { Icon(MaterialSymbols.Rounded.Photo, contentDescription = null, Modifier.size(16.dp)) },
            )
        }
        items(tags, key = { "tag-$it" }) { tag ->
            FilterChip(
                selected = state.selectedTags.hasIgnoreCase(tag),
                onClick = { onToggleTag(tag) },
                label = { Text(tag) },
            )
        }
    }
}

/** Filter window with two tabs: tags (all selected must match) and series (any selected matches). */
@Composable
private fun AlbumFilterDialog(
    state: AlbumState,
    onToggleTag: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onPinTag: (String) -> Unit,
    onPinSeries: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val tagCounts = remember(state.works) { albumTagCounts(state.works) }
    val seriesCounts = remember(state.works) { albumSeriesCounts(state.works) }
    val counts = if (tab == 0) tagCounts else seriesCounts
    val selected = if (tab == 0) state.selectedTags else state.selectedSeries
    val pinned = if (tab == 0) state.pinnedTags else state.pinnedSeries
    val shown = counts.filter { query.isBlank() || it.first.contains(query.trim(), true) }
    val anySelected = state.selectedTags.isNotEmpty() || state.selectedSeries.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text("태그" + if (state.selectedTags.isNotEmpty()) " ${state.selectedTags.size}" else "") },
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text("시리즈" + if (state.selectedSeries.isNotEmpty()) " ${state.selectedSeries.size}" else "") },
                )
            }
        },
        text = {
            Column {
                Text(
                    if (tab == 0) "고른 태그를 모두 가진 작품" else "고른 시리즈 중 하나인 작품",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(if (tab == 0) "태그 검색" else "시리즈 검색") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                if (state.metaTotal > 0) {
                    Text(
                        "작품 정보를 읽는 중이에요… (${state.metaDone}/${state.metaTotal})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (counts.isEmpty()) {
                    Text(
                        if (tab == 0) "태그가 없어요 (ComicInfo.xml의 Tags)" else "시리즈가 없어요 (ComicInfo.xml의 Series)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .padding(top = 8.dp),
                ) {
                    items(shown, key = { "$tab-${it.first}" }) { (value, total) ->
                        val isSelected = selected.hasIgnoreCase(value)
                        val isPinned = pinned.hasIgnoreCase(value)
                        val toggle = { if (tab == 0) onToggleTag(value) else onToggleSeries(value) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggle() },
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = { toggle() })
                            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                "$total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { if (tab == 0) onPinTag(value) else onPinSeries(value) }) {
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
            if (anySelected) TextButton(onClick = onClear) { Text("모두 해제") }
        },
    )
}

@Composable
private fun AlbumGrid(
    albumId: String,
    profile: CoverGridProfile,
    works: List<AlbumWork>,
    thumbVersion: Long,
    nameMode: Int,
    onOpen: (AlbumWork) -> Unit,
    onNeedThumb: (AlbumWork) -> Unit,
    onToggleLibrary: (AlbumWork) -> Unit,
    onOpenInNas: (AlbumWork) -> Unit,
) {
    val listState = key(albumId) { rememberLazyListState() }
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
                .coverGridPinch(profile, columns, mode),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        ) {
            coverGridItems(
                items = works,
                key = { it.path },
                aspectOf = { albumThumbAspect(it) },
                mode = mode,
                columns = columns,
                spacing = spacing.dp,
                rowHeight = rowHeight.dp,
                contentWidth = maxWidth,
                horizontalPadding = 8.dp,
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
