package mihon.feature.nas

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import kotlinx.coroutines.channels.Channel
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
        val thumbVersion by NasRuntime.thumbs.version.collectAsState()

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

        val nameMode by CoverGridSettings.nas.nameMode.collectAsState()
        var gridOptions by remember { mutableStateOf(false) }
        if (gridOptions) CoverGridOptionsDialog(profile = CoverGridSettings.nas, onDismiss = { gridOptions = false })
        NasBrowserContent(
            state = state,
            thumbVersion = thumbVersion,
            nameMode = nameMode,
            onCycleNames = { CoverGridSettings.nas.cycleNameMode() },
            onGridOptions = { gridOptions = true },
            showBack = !inTab || state.stack.size > 1,
            onBack = { if (state.stack.size > 1) viewModel.goUp() else if (!inTab) navigator.pop() },
            onRefresh = viewModel::reload,
            onOpenSettings = { viewModel.showSettings(true) },
            onOpen = viewModel::open,
            onSetFolderThumb = viewModel::setFolderThumbnail,
            onFolderLongPress = viewModel::onFolderLongPress,
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
                onBackup = viewModel::backup,
                onRestore = viewModel::restore,
            )
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

    fun reload() {
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
                _state.update { it.copy(entries = visible, loading = false) }

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
                    }
                }
                eventChannel.send(NasEvent.Message(if (prompt.inLibrary) "서재에서 제거했어요" else "서재에 추가했어요"))
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("실패: ${e.message}"))
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
                eventChannel.send(NasEvent.Message("폴더 썸네일로 지정했어요"))
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("지정 실패: ${e.message}"))
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val itemsSerializer = ListSerializer(NasThumbStore.Item.serializer())

    private fun backupFile(item: NasThumbStore.Item): String {
        val base = settings.backupPath
        // Table-of-contents thumbnails use "<zip>#<folder in zip>" as path
        if ('#' in item.path) {
            val zip = item.path.substringBefore('#')
            val dir = item.path.substringAfter('#')
            return base + zip + ".toc/" + dir + ".jpg"
        }
        return if (item.folder) childPath(base + item.path, settings.thumbName) else base + item.path + ".jpg"
    }

    fun backup() {
        val base = settings.backupPath
        if (base.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val created = mutableSetOf<String>()
                    val items = thumbs.all()
                    items.forEach { (item, file) ->
                        val target = backupFile(item)
                        dav.mkdirs(parentPath(target), created)
                        dav.put(target, file.readBytes(), "image/jpeg")
                    }
                    dav.mkdirs(base, created)
                    dav.put(
                        childPath(base, "index.json"),
                        json.encodeToString(itemsSerializer, items.map { it.first }).toByteArray(),
                        "application/json",
                    )
                    items.size
                }
                eventChannel.send(NasEvent.Message("썸네일 ${count}개 백업 완료"))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "NAS backup failed" }
                eventChannel.send(NasEvent.Message("백업 실패: ${e.message}"))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun restore() {
        val base = settings.backupPath
        if (base.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val index = dav.get(childPath(base, "index.json")) ?: error("백업이 없어요")
                    val items = json.decodeFromString(itemsSerializer, index.decodeToString())
                    var n = 0
                    items.forEach { item ->
                        runCatching {
                            dav.get(backupFile(item))?.let {
                                thumbs.put(item.path, item.folder, it, alreadyThumbnail = true)
                                n++
                            }
                        }
                    }
                    n
                }
                eventChannel.send(NasEvent.Message("썸네일 ${count}개 복원 완료"))
            } catch (e: Exception) {
                eventChannel.send(NasEvent.Message("복원 실패: ${e.message}"))
            } finally {
                _state.update { it.copy(busy = false) }
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
                    val mode by CoverGridSettings.nas.aspectMode.collectAsState()
                    val spacing by CoverGridSettings.nas.spacing.collectAsState()
                    val rowHeight by CoverGridSettings.nas.rowHeight.collectAsState()
                    val savedColumns by CoverGridSettings.nas.columns.collectAsState()
                    val columns = CoverGridSettings.columnsOrDefault(savedColumns, maxWidth.value)
                    val anyThumb = remember(state.entries, thumbVersion) {
                        state.entries.any { NasRuntime.thumbs.has(it.path, folder = it.isDirectory) }
                    }
                    val layoutMode = if (anyThumb) mode else CoverGridSettings.MODE_SQUARE
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .pinchSteps(columns to layoutMode) { bigger ->
                                if (layoutMode == CoverGridSettings.MODE_JUSTIFIED) {
                                    CoverGridSettings.nas.setRowHeight(
                                        CoverGridSettings.stepRowHeight(CoverGridSettings.nas.rowHeight.value, bigger),
                                    )
                                } else {
                                    CoverGridSettings.nas.setColumns(CoverGridSettings.stepColumns(columns, bigger))
                                }
                            },
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    ) {
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
                                modifier = modifier,
                            )
                        }
                    }
                }
            }
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
                onLongClick = {
                    when {
                        !entry.isDirectory -> menu = true
                        // Library entry only for folders that have a thumbnail (zip check happens next)
                        thumb != null -> onFolderLongPress()
                    }
                },
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
                DropdownMenuItem(
                    text = { Text("폴더 썸네일로 지정") },
                    onClick = {
                        menu = false
                        onSetFolderThumb()
                    },
                )
            }
        }
        // Name rules only apply to tiles with a thumbnail; icon tiles always show the full name.
        // "No brackets" applies to folders only (files keep tags like "[단]").
        val baseName = if (entry.isDirectory) entry.name else entry.name.substringBeforeLast('.')
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
) {
    var server by remember { mutableStateOf(settings.serverUrl) }
    var user by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf(settings.password) }
    var root by remember { mutableStateOf(settings.rootPath) }
    var thumbName by remember { mutableStateOf(settings.thumbName) }
    var backup by remember { mutableStateOf(settings.backupPath) }
    var thumbSize by remember { mutableStateOf(settings.thumbSize) }

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
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NAS 설정") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                HorizontalDivider()
                OutlinedTextField(
                    value = backup,
                    onValueChange = { backup = it },
                    label = { Text("썸네일 백업 폴더") },
                    placeholder = { Text("/pComic/_thumbs") },
                    singleLine = true,
                )
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
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
