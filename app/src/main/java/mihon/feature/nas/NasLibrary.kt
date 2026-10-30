package mihon.feature.nas

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.feature.grid.CoverGridActions
import mihon.feature.grid.CoverGridProfile
import mihon.feature.grid.CoverGridSettings
import mihon.feature.grid.coverGridItems
import mihon.feature.grid.coverGridPinch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.SortByAlpha
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Custom: the library tab in the same style as the NAS browser. Shows the NAS series added to the
 * library as folder tiles; tapping one opens it in the NAS screen.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class NasLibraryViewModel(
    getFavorites: GetFavorites,
    private val updateManga: eu.kanade.domain.manga.interactor.UpdateManga,
) : ViewModel() {

    val items: StateFlow<List<Manga>?> = getFavorites.subscribe(NasSource.SOURCE_ID)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val app: android.app.Application by injectLazy()
    private val prefs by lazy { app.getSharedPreferences("nas_library", Context.MODE_PRIVATE) }

    val sort = MutableStateFlow(prefs.getInt("sort", SORT_NAME))
    val ascending = MutableStateFlow(prefs.getBoolean("ascending", true))
    val query = MutableStateFlow("")

    fun setSort(v: Int) {
        sort.value = v
        prefs.edit().putInt("sort", v).apply()
    }

    fun toggleAscending() {
        ascending.value = !ascending.value
        prefs.edit().putBoolean("ascending", ascending.value).apply()
    }

    fun remove(manga: Manga) {
        viewModelScope.launch(Dispatchers.IO) { updateManga.awaitUpdateFavorite(manga.id, false) }
    }

    private val thumbRequested = mutableSetOf<String>()
    private val thumbLimit = Semaphore(3)

    /** Same order as the NAS browser: phone → NAS backup → thumbnail file in the folder → first archive. */
    fun ensureThumb(path: String) {
        if (NasRuntime.thumbs.has(path, folder = true)) return
        synchronized(thumbRequested) { if (!thumbRequested.add(path)) return }
        viewModelScope.launch(Dispatchers.IO) {
            thumbLimit.withPermit {
                runCatching {
                    val settings = NasRuntime.settings
                    NasRuntime.restoreOrMake(path, folder = true) {
                        runCatching { NasRuntime.dav.get(childPath(path, settings.thumbName)) }.getOrNull()
                    }
                    if (!NasRuntime.thumbs.has(path, folder = true)) {
                        // chaptersFor lists the last volume first
                        val firstZip = NasSource.chaptersFor(path).lastOrNull()?.url ?: return@runCatching
                        NasRuntime.createArchiveThumbnail(firstZip)
                        NasRuntime.thumbs.bytes(firstZip, folder = false)?.let {
                            NasRuntime.thumbs.put(path, folder = true, imageBytes = it, alreadyThumbnail = true)
                            NasRuntime.backupOne(path, folder = true)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val SORT_NAME = 0
        const val SORT_ADDED = 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasLibraryContent() {
    val navigator = LocalNavigator.currentOrThrow
    val tabNavigator = LocalTabNavigator.current
    val viewModel = metroViewModel<NasLibraryViewModel>()
    val items by viewModel.items.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val ascending by viewModel.ascending.collectAsState()
    val query by viewModel.query.collectAsState()
    val thumbVersion by NasRuntime.thumbs.version.collectAsState()
    // Library settings (shared with series opened from the library)
    val profile = CoverGridSettings.profileById("library")
    val nameMode by profile.nameMode.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }

    val shown = remember(items, sort, ascending, query) {
        val list = items.orEmpty().filter {
            query.isBlank() || it.title.contains(query, true) || fileName(it.url).contains(query, true)
        }
        val sorted = when (sort) {
            NasLibraryViewModel.SORT_ADDED -> list.sortedBy { it.favoriteAt }
            else -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
        if (ascending) sorted else sorted.reversed()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("서재", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            items?.let { all ->
                                Text(
                                    if (shown.size == all.size) "${all.size}작품" else "${shown.size} / ${all.size}작품",
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
                        CoverGridActions(profile = profile)
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) viewModel.query.value = ""
                        }) {
                            Icon(MaterialSymbols.Rounded.Search, contentDescription = "검색")
                        }
                        Box {
                            var more by remember { mutableStateOf(false) }
                            IconButton(onClick = { more = true }) {
                                Icon(MaterialSymbols.Rounded.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                listOf(
                                    NasLibraryViewModel.SORT_NAME to "이름순",
                                    NasLibraryViewModel.SORT_ADDED to "추가한 순",
                                ).forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(if (sort == value) "✓ $label" else label) },
                                        leadingIcon = { Icon(MaterialSymbols.Rounded.SortByAlpha, contentDescription = null) },
                                        onClick = {
                                            viewModel.setSort(value)
                                            more = false
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (ascending) "오름차순" else "내림차순") },
                                    leadingIcon = {
                                        Icon(
                                            if (ascending) MaterialSymbols.Rounded.ArrowUpward else MaterialSymbols.Rounded.ArrowDownward,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = { viewModel.toggleAscending() },
                                )
                            }
                        }
                    },
                )
                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder = { Text("제목 검색") },
                        singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.query.value = "" }) {
                                    Icon(MaterialSymbols.Rounded.Close, contentDescription = null)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
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
                items == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                items.orEmpty().isEmpty() -> Text(
                    "서재가 비어 있어요\nNAS 탭에서 폴더를 길게 눌러 추가하세요",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
                shown.isEmpty() -> Text("조건에 맞는 작품이 없어요", modifier = Modifier.align(Alignment.Center))
                else -> NasLibraryGrid(
                    profile = profile,
                    items = shown,
                    thumbVersion = thumbVersion,
                    nameMode = nameMode,
                    onOpen = { manga ->
                        navigator.push(NasBrowserScreen(startPath = manga.url, gridProfileId = profile.id))
                    },
                    onNeedThumb = { viewModel.ensureThumb(it.url) },
                    onRemove = viewModel::remove,
                    onOpenInNas = { manga ->
                        val stack = NasFavorites.stackFor(manga.url)
                        NasRuntime.settings.lastStack = stack
                        NasRuntime.pendingJump.value = stack
                        tabNavigator.current = NasTab
                    },
                )
            }
        }
    }
}

@Composable
private fun NasLibraryGrid(
    profile: CoverGridProfile,
    items: List<Manga>,
    thumbVersion: Long,
    nameMode: Int,
    onOpen: (Manga) -> Unit,
    onNeedThumb: (Manga) -> Unit,
    onRemove: (Manga) -> Unit,
    onOpenInNas: (Manga) -> Unit,
) {
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
                .coverGridPinch(profile, columns, mode),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        ) {
            coverGridItems(
                items = items,
                key = { it.id },
                aspectOf = { manga ->
                    NasRuntime.thumbs.get(manga.url, folder = true)?.let { libraryThumbAspect(it) }
                },
                mode = mode,
                columns = columns,
                spacing = spacing.dp,
                rowHeight = rowHeight.dp,
                contentWidth = maxWidth,
                horizontalPadding = 8.dp,
            ) { manga, modifier, boxAspect, crop ->
                NasLibraryTile(
                    manga = manga,
                    thumbVersion = thumbVersion,
                    nameMode = nameMode,
                    boxAspect = boxAspect,
                    crop = crop,
                    onClick = { onOpen(manga) },
                    onNeedThumb = { onNeedThumb(manga) },
                    onRemove = { onRemove(manga) },
                    onOpenInNas = { onOpenInNas(manga) },
                    modifier = modifier,
                )
            }
        }
    }
}

private val libraryAspects = java.util.concurrent.ConcurrentHashMap<String, Float>()

/** Thumbnail ratio (width / height), read once from the image header. */
private fun libraryThumbAspect(file: File): Float? {
    val key = "${file.path}:${file.lastModified()}"
    libraryAspects[key]?.let { return it }
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return (options.outWidth.toFloat() / options.outHeight).also { libraryAspects[key] = it }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NasLibraryTile(
    manga: Manga,
    thumbVersion: Long,
    nameMode: Int,
    boxAspect: Float,
    crop: Boolean,
    onClick: () -> Unit,
    onNeedThumb: () -> Unit,
    onRemove: () -> Unit,
    onOpenInNas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val thumb: File? = remember(manga.url, thumbVersion) { NasRuntime.thumbs.get(manga.url, folder = true) }
    LaunchedEffect(manga.url, thumb == null) {
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
                    contentDescription = manga.title,
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
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("NAS 탭에서 열기") },
                    onClick = {
                        menu = false
                        onOpenInNas()
                    },
                )
                val favorite = remember(menu) { NasFavorites.contains(manga.url) }
                DropdownMenuItem(
                    text = { Text(if (favorite) "즐겨찾기에서 제거" else "즐겨찾기에 추가") },
                    onClick = {
                        menu = false
                        val added = NasFavorites.toggle(manga.url)
                        Toast.makeText(
                            context,
                            if (added) "즐겨찾기에 추가했어요" else "즐겨찾기에서 제거했어요",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                DropdownMenuItem(
                    text = { Text("서재에서 제거") },
                    onClick = {
                        menu = false
                        onRemove()
                        Toast.makeText(context, "서재에서 제거했어요", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        val name = when {
            thumb == null -> manga.title
            nameMode == CoverGridSettings.NAME_HIDE -> null
            nameMode == CoverGridSettings.NAME_NO_BRACKETS -> CoverGridSettings.stripBrackets(manga.title)
            else -> manga.title
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
