package mihon.feature.nas

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.roundedfilled.Folder

/**
 * Custom: favorite NAS folders (added by long-pressing a folder in the NAS tab).
 */
object NasFavorites {
    private val _paths = MutableStateFlow(NasRuntime.settings.favorites)
    val paths: StateFlow<List<String>> = _paths.asStateFlow()

    fun contains(path: String) = path in _paths.value

    fun toggle(path: String): Boolean {
        val added = path !in _paths.value
        val list = if (added) _paths.value + path else _paths.value - path
        NasRuntime.settings.favorites = list
        _paths.value = list
        return added
    }

    /** Folder stack from the start folder down to [path], so going back works in the NAS tab. */
    fun stackFor(path: String): List<String> {
        val root = NasRuntime.settings.rootPath
        if (path == root) return listOf(root)
        if (root != "/" && !path.startsWith("$root/")) return listOf(root)
        val stack = mutableListOf(root)
        val rest = if (root == "/") path.trim('/') else path.removePrefix("$root/")
        var current = root
        rest.split('/').filter { it.isNotEmpty() }.forEach {
            current = childPath(current, it)
            stack += current
        }
        return stack
    }
}

data object FavoritesTab : eu.kanade.presentation.util.Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 6u,
            title = "즐겨찾기",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Bookmark),
        )

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val tabNavigator = LocalTabNavigator.current
        val paths by NasFavorites.paths.collectAsState()
        val thumbVersion by NasRuntime.thumbs.version.collectAsState()
        var removing by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("즐겨찾기") },
                    navigationIcon = {
                        mihon.feature.grid.LocalNavBarToggle.current?.let { toggle ->
                            IconButton(onClick = toggle) {
                                Icon(MaterialSymbols.Rounded.DragHandle, contentDescription = null)
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (paths.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "NAS 탭에서 폴더를 길게 눌러\n즐겨찾기에 추가하세요",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(paths, key = { it }) { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    val stack = NasFavorites.stackFor(path)
                                    NasRuntime.settings.lastStack = stack
                                    NasRuntime.pendingJump.value = stack
                                    tabNavigator.current = NasTab
                                },
                                onLongClick = { removing = path },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val thumb = remember(path, thumbVersion) { NasRuntime.thumbs.get(path, folder = true) }
                        Box(
                            modifier = Modifier
                                .size(48.dp, 64.dp)
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
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    MaterialSymbols.RoundedFilled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                fileName(path).ifEmpty { "/" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        removing?.let { path ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(fileName(path).ifEmpty { "/" }) },
                text = { Text("즐겨찾기에서 제거할까요?") },
                confirmButton = {
                    TextButton(onClick = {
                        NasFavorites.toggle(path)
                        removing = null
                    }) { Text("제거") }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } },
            )
        }
    }
}
