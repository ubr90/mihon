package mihon.feature.nas

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Book
import mihon.icons.materialsymbols.rounded.Code
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Storage
import mihon.feature.grid.CoverGridOptionsDialog
import mihon.feature.grid.CoverGridSettings
import mihon.feature.grid.coverGridItems
import mihon.feature.grid.pinchSteps
import mihon.icons.materialsymbols.roundedfilled.Folder
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.ViewModule
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom: NAS (WebDAV) folder browser. Folders and zip/cbz archives are shown as tiles;
 * tapping an archive opens it in the reader.
 */
class NasBrowserScreen(private val inTab: Boolean = false) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<NasBrowserViewModel>()
        val state by viewModel.state.collectAsState()
        val thumbOnlyVersion by NasRuntime.thumbs.version.collectAsState()
        val metaVersion by NasRuntime.meta.version.collectAsState()
        // Tiles reload when a thumbnail or metadata (ComicInfo) changes
        val thumbVersion = thumbOnlyVersion + metaVersion

        LaunchedEffect(Unit) {
            viewModel.events.collect { event ->
                when (event) {
                    is NasEvent.OpenReader -> context.startActivity(
                        ReaderActivity.newIntent(context, event.mangaId, event.chapterId),
                    )
                    is NasEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                }
            }
        }

        BackHandler(enabled = state.stack.size > 1) { viewModel.goUp() }
        if (inTab) {
            LaunchedEffect(state.stack.size) { NasRuntime.stackDepth.value = state.stack.size }
        }

        // Grid settings: the folder's own when "only this folder" is locked, otherwise the shared NAS ones
        val lockedDirs by CoverGridSettings.nasLocked.collectAsState()
        val gridProfile = remember(state.currentPath, state.entries, lockedDirs) {
            CoverGridSettings.nasProfileFor(state.currentPath)
        }
        val nameMode by gridProfile.nameMode.collectAsState()
        var gridOptions by remember { mutableStateOf(false) }
        if (gridOptions) {
            CoverGridOptionsDialog(
                profile = gridProfile,
                onDismiss = { gridOptions = false },
                lockPath = state.currentPath,
            )
        }
        NasBrowserContent(
            state = state,
            thumbVersion = thumbVersion,
            nameMode = nameMode,
            gridProfile = gridProfile,
            onCycleNames = { gridProfile.cycleNameMode() },
            onGridOptions = { gridOptions = true },
            showBack = !inTab || state.stack.size > 1,
            onBack = { if (state.stack.size > 1) viewModel.goUp() else if (!inTab) navigator.pop() },
            onRefresh = viewModel::refresh,
            onOpenSettings = { viewModel.showSettings(true) },
            onOpen = viewModel::open,
            onSetFolderThumb = viewModel::setFolderThumbnail,
            onFolderLongPress = viewModel::onFolderLongPress,
            onDeleteThumb = viewModel::deleteFileThumbnail,
            onRestoreThumb = viewModel::restoreFileThumbnail,
        )

        state.libraryPrompt?.let { prompt ->
            AlertDialog(
                onDismissRequest = viewModel::dismissLibraryPrompt,
                title = { Text(fileName(prompt.entry.path)) },
                text = { Text(if (prompt.inLibrary) "서재에서 제거할까요?" else "서재에 추가할까요?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmLibrary(prompt) }) {
                        Text(if (prompt.inLibrary) "서재에서 제거" else "서재에 추가")
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissLibraryPrompt) { Text("취소") } },
            )
        }

        if (state.showSettings) {
            NasSettingsDialog(
                settings = NasRuntime.settings,
                busy = state.busy,
                onDismiss = { viewModel.showSettings(false) },
                onSaved = { connectionChanged ->
                    viewModel.showSettings(false)
                    // Only go back to the start folder when the server or start folder changed
                    if (connectionChanged || state.currentPath == null) viewModel.resetToRoot() else viewModel.reload()
                },
                onBackup = viewModel::startBackup,
                onRestore = viewModel::startRestore,
                onDelete = viewModel::startDelete,
            )
        }

        state.selection?.let { selection ->
            NasFolderSelectDialog(
                selection = selection,
                onConfirm = viewModel::confirmSelection,
                onDismiss = viewModel::dismissSelection,
            )
        }
        state.confirm?.let { confirm ->
            AlertDialog(
                onDismissRequest = viewModel::dismissConfirm,
                title = { Text(confirm.title) },
                text = { Text(confirm.text) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissConfirm()
                        confirm.onConfirm()
                    }) { Text(confirm.confirmLabel) }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissConfirm) { Text("취소") } },
            )
        }
        state.progress?.let { progress ->
            NasProgressDialog(progress = progress, onCancel = viewModel::cancelJob)
        }
    }
}

data class LibraryPrompt(val entry: NasEntry, val mangaId: Long, val inLibrary: Boolean)

sealed interface NasEvent {
    data class OpenReader(val mangaId: Long, val chapterId: Long) : NasEvent
    data class Message(val text: String) : NasEvent
}

data class NasState(
    val stack: List<String> = emptyList(),
    val entries: List<NasEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val showSettings: Boolean = false,
    val libraryPrompt: LibraryPrompt? = null,
    val busy: Boolean = false,
    val selection: NasSelection? = null,
    val confirm: NasConfirm? = null,
    val progress: NasProgress? = null,
    /** Folder the current [entries] belong to (the scroll position is remembered per folder). */
    val entriesPath: String? = null,
) {
    val currentPath: String? get() = stack.lastOrNull()
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class NasBrowserViewModel(
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateManga: eu.kanade.domain.manga.interactor.UpdateManga,
    private val coverCache: eu.kanade.tachiyomi.data.cache.CoverCache,
    private val updateMangaFromRemote: mihon.domain.source.interactor.UpdateMangaFromRemote,
) : ViewModel() {

    private val settings get() = NasRuntime.settings
    private val dav get() = NasRuntime.dav
    private val thumbs get() = NasRuntime.thumbs

    private val _state = MutableStateFlow(NasState())
    val state: StateFlow<NasState> = _state.asStateFlow()

    private val eventChannel = Channel<NasEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** Folders already checked for a thumbnail image during this session. */
    private val probed = mutableSetOf<String>()
    private val probeLimit = Semaphore(4)

    init {
        // Favorites tab asks to open a folder
        viewModelScope.launch {
            NasRuntime.pendingJump.collect { stack ->
                if (stack != null && settings.isConfigured) {
                    NasRuntime.pendingJump.value = null
                    setStack(resolveRenamed(stack))
                    _state.update { it.copy(entries = emptyList()) }
                    reload()
                }
            }
        }
        when {
            !settings.isConfigured -> showSettings(true)
            else -> {
                // Restore the folder path from the last visit (the screen can be recreated while reading)
                val root = settings.rootPath
                val saved = settings.lastStack.filter { it == root || it.startsWith("$root/") || root == "/" }
                if (saved.firstOrNull() == root) {
                    _state.update { it.copy(stack = saved) }
                    reload()
                } else {
                    resetToRoot()
                }
            }
        }
    }

    /**
     * A favorite may point to a folder whose "[memo]" was edited since: find the folder with the
     * same name without the memo and use (and remember) its current name.
     */
    private suspend fun resolveRenamed(stack: List<String>): List<String> {
        val target = stack.lastOrNull() ?: return stack
        if (stack.size < 2) return stack
        return withContext(Dispatchers.IO) {
            runCatching {
                val parent = parentPath(target)
                val dirs = dav.list(parent).filter { it.isDirectory }
                if (dirs.any { NasSettings.normalizePath(it.path) == target }) return@runCatching stack
                val wanted = NasThumbStore.stripMemo(fileName(target))
                val found = dirs.firstOrNull { NasThumbStore.stripMemo(it.name) == wanted } ?: return@runCatching stack
                val newPath = NasSettings.normalizePath(found.path)
                NasFavorites.rename(target, newPath)
                stack.dropLast(1) + newPath
            }.getOrDefault(stack)
        }
    }

    private fun setStack(stack: List<String>) {
        settings.lastStack = stack
        _state.update { it.copy(stack = stack) }
    }

    fun showSettings(show: Boolean) = _state.update { it.copy(showSettings = show) }

    fun resetToRoot() {
        probed.clear()
        setStack(listOf(settings.rootPath))
        reload()
    }

    fun goUp() {
        setStack(_state.value.stack.dropLast(1))
        reload()
    }

    /** Refresh button: list again, then take this folder's thumbnails from the NAS backup (overwriting). */
    fun refresh() {
        _state.value.currentPath?.let { RemoteZip.forgetFolder(it) }
        probed.clear()
        reload(restoreFromBackup = true)
    }

    fun reload(restoreFromBackup: Boolean = false) {
        val path = _state.value.currentPath ?: return
        if (!settings.isConfigured) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val thumbName = settings.thumbName
                val list = withContext(Dispatchers.IO) { dav.list(path) }
                val visible = list
                    .filterNot { it.name.equals(thumbName, ignoreCase = true) }
                    .filterNot { it.name.startsWith(".") || it.name.startsWith("@") || it.name.startsWith("#") }
                    .filter { it.isDirectory || isArchiveName(it.name) }
                    .sortedWith(compareBy<NasEntry> { !it.isDirectory }.thenBy(NaturalOrder) { it.name })
                if (_state.value.currentPath != path) return@launch
                // A folder holding archives is matched without its "[memo]"
                if (visible.any { !it.isDirectory }) thumbs.markLeaf(path)
                _state.update { it.copy(entries = visible, loading = false, entriesPath = path) }
                if (restoreFromBackup) restoreVisible(path, visible)

                // Thumbnail image inside the current folder counts for this folder
                if (!thumbs.has(path, folder = true) && list.any { it.name.equals(thumbName, true) }) {
                    probeFolder(path)
                }
                visible.filter { it.isDirectory }.forEach { probeFolder(it.path) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "NAS list failed" }
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Restores the thumbnails of the items shown in this folder (folders: folder thumbnail,
     * archives: archive thumbnail + ComicInfo) from the backup folder, overwriting the phone copies.
     */
    private fun restoreVisible(folderPath: String, entries: List<NasEntry>) {
        val base = settings.backupPath
        viewModelScope.launch {
            val n = AtomicInteger()
            if (base.isNotEmpty()) withContext(Dispatchers.IO) {
                val limit = Semaphore(6)
                coroutineScope {
                    entries.forEach { entry ->
                        launch {
                            limit.withPermit {
                                val folder = entry.isDirectory
                                // Memo-free path first (PC tool / new backups), then the exact one (older app backups).
                                // A sub folder not known to hold archives yet is also tried without its memo.
                                val asLeaf = if (folder) thumbs.canonAsLeaf(entry.path) else null
                                val paths = listOfNotNull(thumbs.canon(entry.path, folder), asLeaf, entry.path).distinct()
                                var foundAt: String? = null
                                val bytes = paths.firstNotNullOfOrNull { p ->
                                    runCatching { dav.get(backupFile(NasThumbStore.Item(p, folder))) }.getOrNull()
                                        ?.also { foundAt = p }
                                }
                                if (bytes != null) {
                                    if (folder && foundAt == asLeaf) thumbs.markLeaf(entry.path)
                                    thumbs.put(entry.path, folder, bytes, alreadyThumbnail = true, save = false)
                                    n.incrementAndGet()
                                }
                                if (!folder) {
                                    paths.firstNotNullOfOrNull { p ->
                                        runCatching { dav.get(base + p + ".ComicInfo.xml") }.getOrNull()
                                    }?.let { NasRuntime.meta.put(entry.path, it, notify = false) }
                                }
                            }
                        }
                    }
                }
                // Table-of-contents thumbnails of the archives here, found through index.json
                val zips = entries.filter { !it.isDirectory }
                if (zips.isNotEmpty()) {
                    runCatching {
                        val index = dav.get(childPath(base, "index.json"))?.let {
                            json.decodeFromString(itemsSerializer, it.decodeToString())
                        }.orEmpty()
                        val wanted = zips.associateBy { thumbs.canon(it.path, folder = false) }
                        val tocItems = index.filter { '#' in it.path && thumbs.canon(it.path.substringBefore('#'), false) in wanted }
                        val limit = Semaphore(6)
                        coroutineScope {
                            tocItems.forEach { item ->
                                launch {
                                    limit.withPermit {
                                        runCatching { dav.get(backupFile(item)) }.getOrNull()?.let {
                                            thumbs.put(item.path, folder = false, it, alreadyThumbnail = true, save = false)
                                            n.incrementAndGet()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                thumbs.flush()
                NasRuntime.meta.notifyChanged()
            }
            // Archive names, order and series info of this folder (library entry included) from the NAS
            if (entries.any { !it.isDirectory }) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val manga = networkToLocalManga(NasSource.mangaFor(folderPath).toDomainManga(NasSource.SOURCE_ID))
                        updateMangaFromRemote(NasSource, manga, fetchDetails = true, fetchChapters = true)
                    }
                }
            }
            eventChannel.send(
                NasEvent.Message(
                    when {
                        base.isEmpty() -> "새로고침했어요 (백업 폴더 미지정)"
                        n.get() > 0 -> "새로고침 · 백업에서 썸네일 ${n.get()}개 받았어요"
                        else -> "새로고침했어요 (백업에 이 폴더 썸네일 없음)"
                    },
                ),
            )
        }
    }

    /** Rule 1: a thumbnail image file inside the folder. */
    private fun probeFolder(folderPath: String) {
        if (thumbs.has(folderPath, folder = true) || !probed.add(folderPath)) return
        viewModelScope.launch(Dispatchers.IO) {
            probeLimit.withPermit {
                runCatching {
                    dav.get(childPath(folderPath, settings.thumbName))?.let { bytes ->
                        thumbs.put(folderPath, folder = true, imageBytes = bytes)
                    }
                }
            }
        }
    }

    fun open(entry: NasEntry) {
        if (entry.isDirectory) {
            setStack(_state.value.stack + entry.path)
            _state.update { it.copy(entries = emptyList()) }
            reload()
            return
        }
        viewModelScope.launch {
            try {
                val (mangaId, chapterId) = withContext(Dispatchers.IO) {
                    val folder = parentPath(entry.path)
                    val manga = networkToLocalManga(
                        NasSource.mangaFor(folder).toDomainManga(NasSource.SOURCE_ID),
                    )
                    syncChaptersWithSource.await(NasSource.chaptersFor(folder), manga, NasSource)
                    val chapter = getChaptersByMangaId.await(manga.id).first { it.url == entry.path }
                    manga.id to chapter.id
                }
                eventChannel.send(NasEvent.OpenReader(mangaId, chapterId))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "NAS open failed" }
                eventChannel.send(NasEvent.Message("열기 실패: ${e.message}"))
            }
        }
    }

    /** Long-press on a folder with a thumbnail: offer adding it (as a series) to the library. */
    fun onFolderLongPress(entry: NasEntry) {
        viewModelScope.launch {
            try {
                val prompt = withContext(Dispatchers.IO) {
                    val chapters = NasSource.chaptersFor(entry.path)
                    if (chapters.isEmpty()) return@withContext null
                    val manga = networkToLocalManga(NasSource.mangaFor(entry.path).toDomainManga(NasSource.SOURCE_ID))
                    LibraryPrompt(entry, manga.id, manga.favorite)
                }
                if (prompt == null) {
                    eventChannel.send(NasEvent.Message("zip 파일이 없는 폴더예요"))
                } else {
                    _state.update { it.copy(libraryPrompt = prompt) }
                }
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("확인 실패: ${e.message}"))
            }
        }
    }

    fun dismissLibraryPrompt() = _state.update { it.copy(libraryPrompt = null) }

    fun confirmLibrary(prompt: LibraryPrompt) {
        _state.update { it.copy(libraryPrompt = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val folder = prompt.entry.path
                    val manga = networkToLocalManga(NasSource.mangaFor(folder).toDomainManga(NasSource.SOURCE_ID))
                    if (prompt.inLibrary) {
                        updateManga.awaitUpdateFavorite(manga.id, false)
                    } else {
                        syncChaptersWithSource.await(NasSource.chaptersFor(folder), manga, NasSource)
                        // Library cover = the folder's NAS thumbnail
                        thumbs.get(folder, folder = true)?.inputStream()?.use { coverCache.setCustomCoverToCache(manga, it) }
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                        updateManga.awaitUpdateFavorite(manga.id, true)
                        // Author, summary and genres from ComicInfo.xml (if restored from the PC tool)
                        updateMangaFromRemote(NasSource, manga, fetchDetails = true)
                    }
                }
                eventChannel.send(NasEvent.Message(if (prompt.inLibrary) "서재에서 제거했어요" else "서재에 추가했어요"))
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("실패: ${e.message}"))
            }
        }
    }

    /** Long-press on an archive: remove its thumbnail (and ComicInfo) from the phone only. */
    fun deleteFileThumbnail(entry: NasEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                NasRuntime.meta.remove(entry.path)
                thumbs.removeAll(listOf(NasThumbStore.Item(entry.path, folder = false)))
                NasRuntime.meta.notifyChanged()
            }
            eventChannel.send(NasEvent.Message("폰에서 썸네일을 지웠어요"))
        }
    }

    /** Long-press on an archive: restore only this archive's thumbnail (and ComicInfo) from the NAS backup. */
    fun restoreFileThumbnail(entry: NasEntry) {
        val base = settings.backupPath
        if (base.isEmpty()) {
            viewModelScope.launch { eventChannel.send(NasEvent.Message("NAS 설정에서 백업 폴더를 먼저 지정하세요")) }
            return
        }
        viewModelScope.launch {
            try {
                val (thumb, meta) = withContext(Dispatchers.IO) {
                    val item = NasThumbStore.Item(entry.path, folder = false)
                    // Backups use the memo-free path (PC tool, new app backups); older app backups the exact one
                    val canonPath = thumbs.canon(entry.path, folder = false)
                    val paths = listOf(canonPath, entry.path).distinct()
                    val image = paths.firstNotNullOfOrNull { p ->
                        runCatching { dav.get(backupFile(NasThumbStore.Item(p, folder = false))) }.getOrNull()
                    }?.also {
                        thumbs.put(item.path, folder = false, imageBytes = it, alreadyThumbnail = true)
                    }
                    val xml = paths.firstNotNullOfOrNull { p ->
                        runCatching { dav.get(base + p + ".ComicInfo.xml") }.getOrNull()
                    }?.also {
                        NasRuntime.meta.put(entry.path, it)
                    }
                    (image != null) to (xml != null)
                }
                eventChannel.send(
                    NasEvent.Message(
                        when {
                            thumb && meta -> "썸네일과 작품 정보를 복원했어요"
                            thumb -> "썸네일을 복원했어요"
                            meta -> "작품 정보만 복원했어요 (백업에 썸네일이 없어요)"
                            else -> "백업에 이 파일의 썸네일이 없어요"
                        },
                    ),
                )
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("복원 실패: ${e.message}"))
            }
        }
    }

    fun setFolderThumbnail(entry: NasEntry) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = NasRuntime.createArchiveThumbnail(entry.path) ?: error("이미지가 없어요")
                    thumbs.put(parentPath(entry.path), folder = true, imageBytes = bytes, alreadyThumbnail = true)
                }
                val note = withContext(Dispatchers.IO) { NasRuntime.backupOne(parentPath(entry.path), folder = true) }
                eventChannel.send(NasEvent.Message("폴더 썸네일로 지정했어요$note"))
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("지정 실패: ${e.message}"))
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val itemsSerializer = ListSerializer(NasThumbStore.Item.serializer())

    private fun backupFile(item: NasThumbStore.Item): String = NasRuntime.backupFilePath(item)

    private var selectionItems: List<NasThumbStore.Item> = emptyList()
    private var restoreNoMeta: Set<String> = emptySet()
    private var job: Job? = null

    /** Step 1 of backup: folder check list from the thumbnails on the phone. */
    fun startBackup() {
        if (settings.backupPath.isEmpty()) return
        val items = thumbs.all().map { it.first }
        selectionItems = items
        _state.update {
            it.copy(
                selection = NasSelection(
                    NasSelection.Mode.BACKUP,
                    nasFolderTree(items.map(::nasItemDir).toSet()),
                    items.map(::nasItemDir),
                ),
            )
        }
    }

    /** Folder check list of the thumbnails on the phone, for deleting them from the phone only. */
    fun startDelete() {
        val items = thumbs.all().map { it.first }
        selectionItems = items
        _state.update {
            it.copy(
                selection = NasSelection(
                    NasSelection.Mode.DELETE,
                    nasFolderTree(items.map(::nasItemDir).toSet()),
                    items.map(::nasItemDir),
                ),
            )
        }
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    /** Step 1 of restore: folder check list from the index.json in the backup folder. */
    fun startRestore() {
        val base = settings.backupPath
        if (base.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val index = dav.get(childPath(base, "index.json")) ?: error("백업이 없어요")
                    // Archives the PC tool found without ComicInfo.xml (no need to ask the NAS for them)
                    restoreNoMeta = runCatching {
                        dav.get(childPath(base, "pc_scan_cache.json"))?.let { bytes ->
                            (json.parseToJsonElement(bytes.decodeToString()) as JsonObject)["no_meta"]
                                .let { it as JsonArray }
                                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                .toSet()
                        }
                    }.getOrNull().orEmpty()
                    json.decodeFromString(itemsSerializer, index.decodeToString())
                }
                selectionItems = items
                val dirs = items.map(::nasItemDir)
                _state.update {
                    it.copy(selection = NasSelection(NasSelection.Mode.RESTORE, nasFolderTree(dirs.toSet()), dirs))
                }
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("복원 실패: ${e.message}"))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissSelection() = _state.update { it.copy(selection = null) }

    fun cancelJob() {
        job?.cancel()
    }

    /** Step 2: back up or restore only the items under the checked folders. */
    fun confirmSelection(checked: Set<String>) {
        val selection = _state.value.selection ?: return
        val items = selectionItems.filterIndexed { i, _ -> nasIsSelected(selection.itemDirs[i], checked) }
        _state.update { it.copy(selection = null) }
        when (selection.mode) {
            NasSelection.Mode.RESTORE -> job = runRestore(items)
            NasSelection.Mode.BACKUP -> confirmBackup(items)
            NasSelection.Mode.DELETE -> _state.update {
                it.copy(
                    confirm = NasConfirm(
                        title = "폰에서 삭제",
                        text = "썸네일 %,d개를 폰에서 지울까요?\nNAS 백업은 그대로 남아요.".format(items.size),
                        confirmLabel = "삭제",
                        onConfirm = { deleteFromPhone(items) },
                    ),
                )
            }
        }
    }

    /** Safety check: tells how many NAS thumbnails will be overwritten before the backup starts. */
    private fun confirmBackup(items: List<NasThumbStore.Item>) {
        val base = settings.backupPath
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val seen = withContext(Dispatchers.IO) {
                    dav.get(childPath(base, "index.json"))?.let { bytes ->
                        runCatching { json.decodeFromString(itemsSerializer, bytes.decodeToString()) }.getOrNull()
                    }.orEmpty().also { thumbs.learnLeaves(it) }.map { thumbs.canon(it.path, it.folder) to it.folder }.toSet()
                }
                val overwrite = items.count { (thumbs.canon(it.path, it.folder) to it.folder) in seen }
                val fresh = items.size - overwrite
                _state.update {
                    it.copy(
                        confirm = NasConfirm(
                            title = "백업",
                            text = "새로 올림 %,d개\n덮어씀 %,d개 (NAS의 같은 썸네일을 폰 것으로 바꿔요)\n\n계속할까요?"
                                .format(fresh, overwrite),
                            confirmLabel = "백업",
                            onConfirm = { job = runBackup(items) },
                        ),
                    )
                }
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("백업 실패: ${e.message}"))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun deleteFromPhone(items: List<NasThumbStore.Item>) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                items.filter { !it.folder && '#' !in it.path }.forEach { NasRuntime.meta.remove(it.path) }
                thumbs.removeAll(items).also { NasRuntime.meta.notifyChanged() }
            }
            eventChannel.send(NasEvent.Message("폰에서 썸네일 ${n}개를 지웠어요"))
        }
    }

    private fun setProgress(label: String, done: Int, total: Int) {
        _state.update { it.copy(progress = NasProgress(label, done, total)) }
    }

    private fun runBackup(items: List<NasThumbStore.Item>): Job {
        val base = settings.backupPath
        setProgress("백업 중", 0, items.size)
        return viewModelScope.launch {
            val created = mutableSetOf<String>()
            val done = java.util.Collections.synchronizedList(mutableListOf<NasThumbStore.Item>())
            val counter = AtomicInteger()
            var failed: Exception? = null
            try {
                withContext(Dispatchers.IO) {
                    val limit = Semaphore(4)
                    coroutineScope {
                        items.forEach { item ->
                            launch {
                                limit.withPermit {
                                    val file = thumbs.file(item.path, item.folder)
                                    if (file.exists()) {
                                        // Saved under the memo-free path, the same rule as the PC tool
                                        val saved = item.copy(path = thumbs.canon(item.path, item.folder))
                                        val target = backupFile(saved)
                                        synchronized(created) { dav.mkdirs(parentPath(target), created) }
                                        dav.put(target, file.readBytes(), "image/jpeg")
                                        done += saved
                                    }
                                    setProgress("백업 중", counter.incrementAndGet(), items.size)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) failed = e
            } finally {
                // Write the index for whatever was uploaded, also after cancel
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { if (done.isNotEmpty()) writeMergedIndex(base, done.toList(), created) }
                        .onFailure { if (failed == null) failed = it as? Exception }
                }
                _state.update { it.copy(progress = null) }
                val msg = when {
                    failed != null -> "백업 실패: ${failed?.message} (${done.size}개 완료)"
                    counter.get() < items.size -> "백업 취소 (${done.size}개 완료)"
                    else -> "썸네일 ${done.size}개 백업 완료"
                }
                withContext(NonCancellable) { eventChannel.send(NasEvent.Message(msg)) }
            }
        }
    }

    /** Merges [items] into the index.json already on the NAS (PC tool results, other phones). */
    private fun writeMergedIndex(base: String, items: List<NasThumbStore.Item>, created: MutableSet<String>) {
        dav.mkdirs(base, created)
        val indexPath = childPath(base, "index.json")
        val existing = dav.get(indexPath)?.let {
            runCatching { json.parseToJsonElement(it.decodeToString()) as? JsonArray }.getOrNull()
        } ?: JsonArray(emptyList())
        // Same item = same path after removing the "[memo]" of its folder: the uploaded (current) name wins
        val uploaded = items.distinct()
        val uploadedKeys = uploaded.map { thumbs.canon(it.path, it.folder) to it.folder }.toSet()
        val kept = existing.filter { element ->
            val obj = element as? JsonObject ?: return@filter true
            val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: return@filter true
            val folder = (obj["folder"] as? JsonPrimitive)?.booleanOrNull ?: false
            (thumbs.canon(path, folder) to folder) !in uploadedKeys
        }
        val added = uploaded.map { json.encodeToJsonElement(NasThumbStore.Item.serializer(), it) }
        dav.put(indexPath, JsonArray(kept + added).toString().toByteArray(), "application/json")
    }

    private fun runRestore(items: List<NasThumbStore.Item>): Job {
        val base = settings.backupPath
        val missingOnly = settings.restoreMissingOnly
        val noMeta = restoreNoMeta
        thumbs.learnLeaves(items)
        setProgress("복원 중", 0, items.size)
        return viewModelScope.launch {
            val n = AtomicInteger()
            val m = AtomicInteger()
            val counter = AtomicInteger()
            var failed: Exception? = null
            try {
                withContext(Dispatchers.IO) {
                    val limit = Semaphore(6)
                    coroutineScope {
                        items.forEach { item ->
                            launch {
                                limit.withPermit {
                                    if (!(missingOnly && thumbs.has(item.path, item.folder))) {
                                        runCatching {
                                            dav.get(backupFile(item))?.let {
                                                thumbs.put(item.path, item.folder, it, alreadyThumbnail = true, save = false)
                                                n.incrementAndGet()
                                            }
                                        }
                                    }
                                    val wantsMeta = !item.folder && '#' !in item.path && item.path !in noMeta
                                    if (wantsMeta && !(missingOnly && NasRuntime.meta.has(item.path))) {
                                        runCatching {
                                            dav.get(base + item.path + ".ComicInfo.xml")?.let {
                                                NasRuntime.meta.put(item.path, it, notify = false)
                                                m.incrementAndGet()
                                            }
                                        }
                                    }
                                    setProgress("복원 중", counter.incrementAndGet(), items.size)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) failed = e
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    thumbs.flush()
                    NasRuntime.meta.notifyChanged()
                }
                _state.update { it.copy(progress = null) }
                val result = "썸네일 ${n.get()}개" + if (m.get() > 0) ", 작품 정보 ${m.get()}개" else ""
                val msg = when {
                    failed != null -> "복원 실패: ${failed?.message} ($result)"
                    counter.get() < items.size -> "복원 취소 ($result 받음)"
                    else -> "$result 복원 완료"
                }
                withContext(NonCancellable) { eventChannel.send(NasEvent.Message(msg)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NasBrowserContent(
    state: NasState,
    thumbVersion: Long,
    nameMode: Int,
    onCycleNames: () -> Unit,
    onGridOptions: () -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (NasEntry) -> Unit,
    onSetFolderThumb: (NasEntry) -> Unit,
    onFolderLongPress: (NasEntry) -> Unit,
    gridProfile: mihon.feature.grid.CoverGridProfile,
    onDeleteThumb: (NasEntry) -> Unit,
    onRestoreThumb: (NasEntry) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.currentPath?.let { fileName(it) }?.ifEmpty { null } ?: "NAS",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    Row {
                        mihon.feature.grid.LocalNavBarToggle.current?.let { toggle ->
                            IconButton(onClick = toggle) {
                                Icon(MaterialSymbols.Rounded.DragHandle, contentDescription = null)
                            }
                        }
                        if (showBack) {
                            IconButton(onClick = onBack) {
                                Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = null)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onGridOptions) {
                        Icon(MaterialSymbols.RoundedFilled.ViewModule, contentDescription = null)
                    }
                    IconButton(onClick = onCycleNames) {
                        Icon(
                            when (nameMode) {
                                CoverGridSettings.NAME_SHOW -> MaterialSymbols.RoundedFilled.Visibility
                                CoverGridSettings.NAME_HIDE -> MaterialSymbols.RoundedFilled.VisibilityOff
                                else -> MaterialSymbols.Rounded.Code
                            },
                            contentDescription = null,
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(MaterialSymbols.Rounded.Refresh, contentDescription = null)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(MaterialSymbols.Rounded.Settings, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && state.entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("불러오지 못했어요\n${state.error}", textAlign = TextAlign.Center)
                    TextButton(onClick = onRefresh) { Text("다시 시도") }
                }
                state.currentPath == null -> TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.Center),
                ) { Text("NAS 설정하기") }
                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val mode by gridProfile.aspectMode.collectAsState()
                    val spacing by gridProfile.spacing.collectAsState()
                    val rowHeight by gridProfile.rowHeight.collectAsState()
                    val savedColumns by gridProfile.columns.collectAsState()
                    val columns = CoverGridSettings.columnsOrDefault(savedColumns, maxWidth.value)
                    val anyThumb = remember(state.entries, thumbVersion) {
                        state.entries.any { NasRuntime.thumbs.has(it.path, folder = it.isDirectory) }
                    }
                    val layoutMode = if (anyThumb) mode else CoverGridSettings.MODE_SQUARE
                    val seriesInfo = remember(state.entries, thumbVersion) {
                        NasRuntime.meta.seriesFor(state.entries.filter { !it.isDirectory }.map { it.path })
                    }
                    // Custom: each folder keeps its own scroll position while the app is running
                    val scrollKey = state.entriesPath ?: state.currentPath ?: ""
                    val listState = androidx.compose.runtime.key(scrollKey) {
                        val saved = NasRuntime.scrollPositions[scrollKey]
                        androidx.compose.foundation.lazy.rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
                    }
                    LaunchedEffect(listState, scrollKey) {
                        androidx.compose.runtime.snapshotFlow {
                            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                        }.collect { NasRuntime.scrollPositions[scrollKey] = it }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pinchSteps(columns to layoutMode) { bigger ->
                                if (layoutMode == CoverGridSettings.MODE_JUSTIFIED) {
                                    gridProfile.setRowHeight(
                                        CoverGridSettings.stepRowHeight(gridProfile.rowHeight.value, bigger),
                                    )
                                } else {
                                    gridProfile.setColumns(CoverGridSettings.stepColumns(columns, bigger))
                                }
                            },
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    ) {
                        if (seriesInfo != null) {
                            item(key = "nas-series-info") { NasSeriesInfo(seriesInfo) }
                        }
                        coverGridItems(
                            items = state.entries,
                            key = { it.path },
                            aspectOf = { nasThumbAspect(it) },
                            mode = layoutMode,
                            columns = columns,
                            spacing = spacing.dp,
                            rowHeight = rowHeight.dp,
                            contentWidth = maxWidth,
                            horizontalPadding = 8.dp,
                        ) { entry, modifier, boxAspect, crop ->
                            NasTile(
                                entry = entry,
                                thumbVersion = thumbVersion,
                                nameMode = nameMode,
                                boxAspect = boxAspect,
                                crop = crop,
                                onClick = { onOpen(entry) },
                                onSetFolderThumb = { onSetFolderThumb(entry) },
                                onFolderLongPress = { onFolderLongPress(entry) },
                                onDeleteThumb = { onDeleteThumb(entry) },
                                onRestoreThumb = { onRestoreThumb(entry) },
                                modifier = modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Komga-like series header from the first archive's ComicInfo.xml. */
@Composable
private fun NasSeriesInfo(info: NasMetaStore.ComicInfo) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { expanded = !expanded }
            .padding(4.dp),
    ) {
        info.series?.let {
            Text(text = it, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val line = listOfNotNull(info.authors, info.publisher, info.year).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val genres = info.genres
        if (genres.isNotEmpty()) {
            Text(
                text = genres.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        info.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NasTile(
    entry: NasEntry,
    thumbVersion: Long,
    nameMode: Int,
    boxAspect: Float,
    crop: Boolean,
    onClick: () -> Unit,
    onSetFolderThumb: () -> Unit,
    onFolderLongPress: () -> Unit,
    onDeleteThumb: () -> Unit,
    onRestoreThumb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val thumb: File? = remember(entry.path, thumbVersion) {
        NasRuntime.thumbs.get(entry.path, folder = entry.isDirectory)
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menu = true },
            )
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
                    contentDescription = entry.name,
                    contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (entry.isDirectory) MaterialSymbols.RoundedFilled.Folder else MaterialSymbols.Rounded.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (entry.isDirectory) {
                    val favorite = remember(menu) { NasFavorites.contains(entry.path) }
                    DropdownMenuItem(
                        text = { Text(if (favorite) "즐겨찾기에서 제거" else "즐겨찾기에 추가") },
                        onClick = {
                            menu = false
                            val added = NasFavorites.toggle(entry.path)
                            Toast.makeText(
                                context,
                                if (added) "즐겨찾기에 추가했어요" else "즐겨찾기에서 제거했어요",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                    // Library entry only for folders that have a thumbnail (zip check happens next)
                    if (thumb != null) {
                        DropdownMenuItem(
                            text = { Text("서재에 추가/제거") },
                            onClick = {
                                menu = false
                                onFolderLongPress()
                            },
                        )
                    }
                } else {
                    DropdownMenuItem(
                        text = { Text("폴더 썸네일로 지정") },
                        onClick = {
                            menu = false
                            onSetFolderThumb()
                        },
                    )
                }
            }
        }
        // Name rules only apply to tiles with a thumbnail; icon tiles always show the full name.
        // "No brackets" applies to folders only (files keep tags like "[단]").
        // Archives show the ComicInfo title (from the PC tool) when there is one
        val baseName = if (entry.isDirectory) {
            entry.name
        } else {
            remember(entry.path, thumbVersion) { NasRuntime.meta.get(entry.path)?.title }
                ?: entry.name.substringBeforeLast('.')
        }
        val shownName = when {
            thumb == null -> baseName
            nameMode == CoverGridSettings.NAME_HIDE -> null
            nameMode == CoverGridSettings.NAME_NO_BRACKETS && entry.isDirectory -> CoverGridSettings.stripBrackets(baseName)
            else -> baseName
        }
        if (shownName != null) {
            Text(
                text = shownName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun NasSettingsDialog(
    settings: NasSettings,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSaved: (connectionChanged: Boolean) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var server by remember { mutableStateOf(settings.serverUrl) }
    var user by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf(settings.password) }
    var root by remember { mutableStateOf(settings.rootPath) }
    var thumbName by remember { mutableStateOf(settings.thumbName) }
    var backup by remember { mutableStateOf(settings.backupPath) }
    var thumbSize by remember { mutableStateOf(settings.thumbSize) }
    var missingOnly by remember { mutableStateOf(settings.restoreMissingOnly) }
    var tab by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }

    fun connectionChanged() = server.trim().trimEnd('/') != settings.serverUrl ||
        user != settings.username ||
        password != settings.password ||
        NasSettings.normalizePath(root) != settings.rootPath

    fun save() {
        settings.thumbSize = thumbSize
        settings.serverUrl = server
        settings.username = user
        settings.password = password
        settings.rootPath = root
        settings.thumbName = thumbName
        settings.backupPath = backup
        settings.restoreMissingOnly = missingOnly
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NAS 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryTabRow(
                    selectedTabIndex = tab,
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    listOf("연결", "썸네일 백업").forEachIndexed { i, title ->
                        androidx.compose.material3.Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(title) },
                        )
                    }
                }
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tab == 0) {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("WebDAV 주소") },
                            placeholder = { Text("https://주소:5006") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(user, { user = it }, label = { Text("아이디") }, singleLine = true)
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("비밀번호") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        OutlinedTextField(
                            value = root,
                            onValueChange = { root = it },
                            label = { Text("시작 폴더") },
                            placeholder = { Text("/pComic/comic") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = thumbName,
                            onValueChange = { thumbName = it },
                            label = { Text("썸네일 파일명") },
                            placeholder = { Text(NasSettings.DEFAULT_THUMB_NAME) },
                            singleLine = true,
                        )
                        Text("썸네일 해상도 (새로 만드는 썸네일부터 적용)", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(480, 700, 1000).forEach { size ->
                                val selected = thumbSize == size
                                OutlinedButton(
                                    onClick = { thumbSize = size },
                                    border = if (selected) {
                                        androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        null
                                    },
                                ) { Text("${size}px") }
                            }
                        }
                    } else {
                        Text("썸네일 백업 폴더", style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = backup.ifBlank { "지정 안 됨" },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                enabled = server.isNotBlank(),
                                onClick = {
                                    save()
                                    picking = true
                                },
                            ) { Text("폴더 선택") }
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { missingOnly = !missingOnly },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = missingOnly, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Column {
                                Text("썸네일 없는 것만")
                                Text(
                                    "복원할 때 폰에 이미 있는 썸네일·정보는 건너뛰어요",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !busy && backup.isNotBlank(),
                                onClick = {
                                    save()
                                    onBackup()
                                },
                            ) { Text("백업하기") }
                            OutlinedButton(
                                enabled = !busy && backup.isNotBlank(),
                                onClick = {
                                    save()
                                    onRestore()
                                },
                            ) { Text("복원하기") }
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = onDelete,
                        ) { Text("폰 썸네일 삭제하기") }
                        Text(
                            "폰에 저장된 썸네일만 지워요. NAS 백업은 지우지 않아요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val changed = connectionChanged()
                save()
                onSaved(changed)
            }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        },
    )

    if (picking) {
        NasFolderPickerDialog(
            start = backup.ifBlank { root },
            onPick = {
                backup = it
                settings.backupPath = it
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** Real ratio (w/h) of a local thumbnail, or null when there is none yet. */
private val nasAspects = java.util.concurrent.ConcurrentHashMap<String, Float>()

private fun nasThumbAspect(entry: NasEntry): Float? {
    val file = NasRuntime.thumbs.get(entry.path, folder = entry.isDirectory) ?: return null
    val key = file.path + ":" + file.lastModified()
    nasAspects[key]?.let { return it }
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    val ratio = opts.outWidth.toFloat() / opts.outHeight
    nasAspects[key] = ratio
    return ratio
}

/**
 * Custom: NAS browser as a main tab (placed before the library).
 */
data object NasTab : eu.kanade.presentation.util.Tab {

    private val screen = NasBrowserScreen(inTab = true)

    override val options: cafe.adriel.voyager.navigator.tab.TabOptions
        @Composable
        get() = cafe.adriel.voyager.navigator.tab.TabOptions(
            index = 5u,
            title = "NAS",
            icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(MaterialSymbols.Rounded.Storage),
        )

    @Composable
    override fun Content() {
        screen.Content()
    }
}
