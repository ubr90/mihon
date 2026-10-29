package mihon.feature.nas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import mihon.icons.materialsymbols.roundedfilled.Folder

/** Custom: progress of a running NAS backup/restore. */
data class NasProgress(val label: String, val done: Int, val total: Int)

/** Custom: folder check list shown before backup/restore. */
data class NasSelection(
    val restore: Boolean,
    /** Folder -> sub folders (natural order). "/" is the top. */
    val tree: Map<String, List<String>>,
    /** Folder each item belongs to (same order as the items in the view model). */
    val itemDirs: List<String>,
)

/** Folder an index item belongs to: folder itself, the archive's folder, or the archive's folder for TOC items. */
fun nasItemDir(item: NasThumbStore.Item): String = when {
    '#' in item.path -> parentPath(item.path.substringBefore('#'))
    item.folder -> item.path
    else -> parentPath(item.path)
}

fun nasFolderTree(dirs: Collection<String>): Map<String, List<String>> {
    val children = HashMap<String, MutableSet<String>>()
    dirs.forEach { dir ->
        var current = dir
        while (current != "/" && current.isNotEmpty()) {
            val parent = parentPath(current)
            if (!children.getOrPut(parent) { mutableSetOf() }.add(current)) break
            current = parent
        }
    }
    return children.mapValues { (_, v) -> v.sortedWith(compareBy(NaturalOrder) { fileName(it) }) }
}

/** True when [dir] is one of the checked folders or below one. */
fun nasIsSelected(dir: String, checked: Set<String>): Boolean =
    checked.any { it == "/" || dir == it || dir.startsWith("$it/") }

@Composable
fun NasFolderSelectDialog(
    selection: NasSelection,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tree = selection.tree
    // Open single-folder chains from the top (e.g. pComic → comic) so the real choices are visible
    var expanded by remember {
        val open = mutableSetOf("/")
        var current = "/"
        while (tree[current]?.size == 1) {
            current = tree[current]!!.first()
            open += current
        }
        mutableStateOf<Set<String>>(open)
    }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val count = remember(checked) { selection.itemDirs.count { nasIsSelected(it, checked) } }

    val rows = remember(expanded, tree) {
        val out = mutableListOf<Pair<String, Int>>()
        fun walk(dir: String, depth: Int) {
            tree[dir].orEmpty().forEach { child ->
                out += child to depth
                if (child in expanded) walk(child, depth + 1)
            }
        }
        walk("/", 0)
        out
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selection.restore) "복원할 폴더" else "백업할 폴더") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { checked = if ("/" in checked) emptySet() else setOf("/") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = "/" in checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text("전체", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
                if (rows.isEmpty()) {
                    Text("항목이 없어요", modifier = Modifier.padding(16.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(rows, key = { it.first }) { (dir, depth) ->
                        val hasChildren = !tree[dir].isNullOrEmpty()
                        val isOpen = dir in expanded
                        val parentChecked = checked.any { it != dir && nasIsSelected(dir, setOf(it)) }
                        val isChecked = parentChecked || dir in checked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = hasChildren) {
                                    expanded = if (isOpen) expanded - dir else expanded + dir
                                }
                                .padding(start = (depth * 16).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                if (hasChildren) {
                                    Icon(
                                        MaterialSymbols.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.rotate(if (isOpen) 90f else 0f),
                                    )
                                }
                            }
                            Checkbox(
                                checked = isChecked,
                                enabled = !parentChecked,
                                onCheckedChange = { on ->
                                    checked = if (on) {
                                        // Checking a folder covers everything below it
                                        checked.filterNot { it.startsWith("$dir/") }.toSet() + dir
                                    } else {
                                        checked - dir
                                    }
                                },
                            )
                            Text(
                                fileName(dir),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = count > 0, onClick = { onConfirm(checked) }) {
                Text(if (selection.restore) "복원 (${count}개)" else "백업 (${count}개)")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun NasProgressDialog(progress: NasProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(progress.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val fraction = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("%,d / %,d (%d%%)".format(progress.done, progress.total, (fraction * 100).toInt()))
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}

/** WebDAV folder picker for the backup folder, with "new folder". */
@Composable
fun NasFolderPickerDialog(
    start: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(NasSettings.normalizePath(start.ifBlank { "/" })) }
    var folders by remember { mutableStateOf<List<NasEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var newFolder by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(current, reloadKey) {
        loading = true
        error = null
        try {
            folders = withContext(Dispatchers.IO) { NasRuntime.dav.list(current) }
                .filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("@") }
                .sortedWith(compareBy(NaturalOrder) { it.name })
        } catch (e: Exception) {
            // The saved folder may not exist any more: go up one level
            if (current != "/") {
                current = parentPath(current)
            } else {
                folders = emptyList()
                error = e.message ?: e.toString()
            }
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(current, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = current != "/", onClick = { current = parentPath(current) }) {
                        Text("↑ 위로")
                    }
                    TextButton(onClick = { newFolder = "" }) { Text("+ 새 폴더") }
                }
                HorizontalDivider()
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
                    error != null -> Text("불러오지 못했어요\n$error", modifier = Modifier.padding(16.dp))
                    folders.isEmpty() -> Text(
                        "하위 폴더 없음",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(folders, key = { it.path }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { current = NasSettings.normalizePath(entry.path) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    MaterialSymbols.RoundedFilled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Box(Modifier.width(12.dp))
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("이 폴더 선택") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )

    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("새 폴더") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { newFolder = it },
                    label = { Text("폴더 이름") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && '/' !in name,
                    onClick = {
                        val path = childPath(current, name.trim())
                        newFolder = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { NasRuntime.dav.mkdirs(path) }
                                current = path
                                reloadKey++
                            } catch (e: Exception) {
                                error = "폴더를 만들지 못했어요: ${e.message}"
                            }
                        }
                    },
                ) { Text("만들기") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("취소") } },
        )
    }
}
