package mihon.feature.nas

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.feature.grid.CoverGridSettings
import mihon.feature.grid.pinchSteps
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredroundedfilled.ViewList
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.roundedfilled.ViewModule

/** One table-of-contents line: a folder inside the archive and the first page in it. */
data class TocEntry(val name: String, val path: String, val depth: Int, val pageIndex: Int)

/**
 * Builds the table of contents from the folder structure of the page paths (in reading order).
 * A single folder wrapping the whole archive is skipped; no folders means no table of contents.
 */
fun buildToc(pagePaths: List<String>): List<TocEntry> {
    val result = mutableListOf<TocEntry>()
    var prev = emptyList<String>()
    pagePaths.forEachIndexed { i, path ->
        val dirs = path.split('/').dropLast(1).filter { it.isNotEmpty() }
        var same = 0
        while (same < dirs.size && same < prev.size && dirs[same] == prev[same]) same++
        for (d in same until dirs.size) {
            result += TocEntry(dirs[d], dirs.take(d + 1).joinToString("/"), d, i)
        }
        prev = dirs
    }
    // Drop a single top folder that wraps everything (e.g. "Vol 1/…")
    val topLevel = result.filter { it.depth == 0 }
    if (topLevel.size == 1 && pagePaths.all { it.contains('/') }) {
        val inner = result.filter { it.depth > 0 }.map { it.copy(depth = it.depth - 1) }
        return if (inner.size <= 1) emptyList() else inner
    }
    return if (result.size <= 1) emptyList() else result
}

/** Thumbnail store key of a table-of-contents entry. */
fun tocThumbPath(zipPath: String, entry: TocEntry) = "$zipPath#${entry.path}"

/**
 * Long-press on a NAS page: opens the table of contents directly (full screen).
 * Shows a message and closes when the archive has no folders.
 */
@Composable
fun NasTocHost(
    page: ReaderPage,
    zipPath: String,
    onDismissRequest: () -> Unit,
    onMoveToPage: (Int) -> Unit,
) {
    val context = LocalContext.current
    var toc by remember { mutableStateOf<List<TocEntry>?>(null) }
    var pagePaths by remember { mutableStateOf<List<ZipEntryInfo>>(emptyList()) }

    LaunchedEffect(zipPath) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val zip = RemoteZip.open(NasRuntime.dav, zipPath)
                val pages = zip.pages(NasRuntime.settings.thumbName)
                pages to buildToc(pages.map { it.name })
            }.getOrNull()
        }
        if (loaded == null || loaded.second.isEmpty()) {
            Toast.makeText(context, "목차가 없어요", Toast.LENGTH_SHORT).show()
            onDismissRequest()
        } else {
            pagePaths = loaded.first
            toc = loaded.second
        }
    }

    val entries = toc ?: return
    NasTocScreen(
        zipPath = zipPath,
        entries = entries,
        pages = pagePaths,
        currentPage = page.index,
        onDismissRequest = onDismissRequest,
        onSelect = { index ->
            onDismissRequest()
            onMoveToPage(index)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NasTocScreen(
    zipPath: String,
    entries: List<TocEntry>,
    pages: List<ZipEntryInfo>,
    currentPage: Int,
    onDismissRequest: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val gridMode by CoverGridSettings.tocGrid.collectAsState()
    // Current entry: the last one starting at or before the current page (deepest level wins)
    val currentIndex = entries.indexOfLast { it.pageIndex <= currentPage }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text("목차") },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(MaterialSymbols.Rounded.Close, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { CoverGridSettings.setTocGrid(!gridMode) }) {
                            Icon(
                                if (gridMode) {
                                    MaterialSymbols.AutoMirroredRoundedFilled.ViewList
                                } else {
                                    MaterialSymbols.RoundedFilled.ViewModule
                                },
                                contentDescription = null,
                            )
                        }
                    },
                )
                if (gridMode) {
                    TocGrid(zipPath, entries, pages, currentIndex, onSelect)
                } else {
                    TocList(entries, currentIndex, onSelect)
                }
            }
        }
    }
}

@Composable
private fun TocList(entries: List<TocEntry>, currentIndex: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(entries) { i, entry ->
            val selected = i == currentIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .clickable { onSelect(entry.pageIndex) }
                    .padding(start = (16 + entry.depth * 20).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text = entry.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.Bold else null,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "${entry.pageIndex + 1}쪽",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TocGrid(
    zipPath: String,
    entries: List<TocEntry>,
    pages: List<ZipEntryInfo>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val thumbVersion by NasRuntime.thumbs.version.collectAsState()
    val savedColumns by CoverGridSettings.tocColumns.collectAsState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = CoverGridSettings.columnsOrDefault(savedColumns, maxWidth.value)
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .pinchSteps(columns) { bigger ->
                    CoverGridSettings.setTocColumns(CoverGridSettings.stepColumns(columns, bigger))
                },
        ) {
            itemsIndexed(entries, key = { _, e -> e.path }) { i, entry ->
                TocTile(
                    zipPath = zipPath,
                    entry = entry,
                    firstPage = pages.getOrNull(entry.pageIndex),
                    selected = i == currentIndex,
                    thumbVersion = thumbVersion,
                    onClick = { onSelect(entry.pageIndex) },
                )
            }
        }
    }
}

@Composable
private fun TocTile(
    zipPath: String,
    entry: TocEntry,
    firstPage: ZipEntryInfo?,
    selected: Boolean,
    thumbVersion: Long,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val key = tocThumbPath(zipPath, entry)
    val thumb = remember(key, thumbVersion) { NasRuntime.thumbs.get(key, folder = false) }

    // Saved permanently with the other thumbnails, so each one is downloaded only once
    LaunchedEffect(key) {
        if (!NasRuntime.thumbs.has(key, folder = false) && firstPage != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    // NAS backup first, otherwise the entry's first page (then backed up)
                    NasRuntime.restoreOrMake(key, folder = false) {
                        RemoteZip.open(NasRuntime.dav, zipPath).read(firstPage)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CoverGridSettings.RATIO_A4)
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
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        Row(modifier = Modifier.padding(top = 2.dp)) {
            Text(
                text = "  ".repeat(entry.depth) + entry.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else null,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
