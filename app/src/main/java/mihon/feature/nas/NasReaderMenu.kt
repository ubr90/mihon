package mihon.feature.nas

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One table-of-contents line: a folder inside the archive and the first page in it. */
data class TocEntry(val name: String, val depth: Int, val pageIndex: Int)

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
        for (d in same until dirs.size) result += TocEntry(dirs[d], d, i)
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

/** Page long-press menu for NAS archives: set as folder thumbnail, table of contents. */
@Composable
fun NasPageActionsDialog(
    page: ReaderPage,
    zipPath: String,
    onDismissRequest: () -> Unit,
    onMoveToPage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toc by remember { mutableStateOf<List<TocEntry>?>(null) }
    var showToc by remember { mutableStateOf(false) }
    var confirmCover by remember { mutableStateOf(false) }

    LaunchedEffect(zipPath) {
        toc = withContext(Dispatchers.IO) {
            runCatching {
                val zip = RemoteZip.open(NasRuntime.dav, zipPath)
                buildToc(zip.pages(NasRuntime.settings.thumbName).map { it.name })
            }.getOrDefault(emptyList())
        }
    }

    if (showToc) {
        NasTocDialog(
            entries = toc.orEmpty(),
            currentPage = page.index,
            onDismissRequest = onDismissRequest,
            onSelect = { index ->
                onDismissRequest()
                onMoveToPage(index)
            },
        )
        return
    }

    if (confirmCover) {
        AlertDialog(
            onDismissRequest = { confirmCover = false },
            text = { Text("이 페이지를 폴더 썸네일로 지정할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCover = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = page.stream?.invoke()?.use { it.readBytes() } ?: error("no image")
                                NasRuntime.thumbs.put(parentPath(zipPath), folder = true, imageBytes = bytes)
                            }.isSuccess
                        }
                        Toast.makeText(
                            context,
                            if (ok) "폴더 썸네일로 지정했어요" else "지정하지 못했어요",
                            Toast.LENGTH_SHORT,
                        ).show()
                        onDismissRequest()
                    }
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { confirmCover = false }) { Text("취소") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { confirmCover = true }, modifier = Modifier.weight(1f)) {
                    Text("표지로 설정")
                }
                val entries = toc
                if (entries == null || entries.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showToc = true },
                        enabled = entries != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("목차 보기") }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun NasTocDialog(
    entries: List<TocEntry>,
    currentPage: Int,
    onDismissRequest: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    // Current entry: the last one starting at or before the current page (deepest level wins)
    val currentIndex = entries.indexOfLast { it.pageIndex <= currentPage }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("목차") },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(entries) { i, entry ->
                    val selected = i == currentIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (selected) {
                                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onSelect(entry.pageIndex) }
                            .padding(start = (12 + entry.depth * 20).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
        },
        confirmButton = { TextButton(onClick = onDismissRequest) { Text("닫기") } },
    )
}
