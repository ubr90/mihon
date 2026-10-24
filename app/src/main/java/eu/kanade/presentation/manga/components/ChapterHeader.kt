package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mihon.feature.grid.CoverGridOptionsDialog
import mihon.feature.grid.CoverGridSettings
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredroundedfilled.ViewList
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.roundedfilled.ViewCompact
import mihon.icons.materialsymbols.roundedfilled.ViewModule
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ChapterHeader(
    enabled: Boolean,
    chapterCount: Int?,
    missingChapterCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewMode: Int? = null,
    onToggleGridView: (() -> Unit)? = null,
    showNames: Boolean? = null,
) {
    var gridOptions by remember { mutableStateOf(false) }
    if (gridOptions) CoverGridOptionsDialog(onDismiss = { gridOptions = false })
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClick = onClick,
            )
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            ChapterHeaderText(chapterCount, missingChapterCount)
        }
        if (showNames != null) {
            // Custom: grid-only mode controls (shared with the NAS browser)
            IconButton(onClick = { CoverGridSettings.setShowNames(!showNames) }, enabled = enabled) {
                Icon(
                    imageVector = if (showNames) {
                        MaterialSymbols.RoundedFilled.Visibility
                    } else {
                        MaterialSymbols.RoundedFilled.VisibilityOff
                    },
                    contentDescription = null,
                )
            }
            IconButton(onClick = { gridOptions = true }, enabled = enabled) {
                Icon(imageVector = MaterialSymbols.Rounded.Settings, contentDescription = null)
            }
        }
        if (viewMode != null && onToggleGridView != null) {
            // Icon shows the mode the button switches to: list -> grid -> grid only -> list
            IconButton(onClick = onToggleGridView, enabled = enabled) {
                Icon(
                    imageVector = when (viewMode) {
                        0 -> MaterialSymbols.RoundedFilled.ViewModule
                        1 -> MaterialSymbols.RoundedFilled.ViewCompact
                        else -> MaterialSymbols.AutoMirroredRoundedFilled.ViewList
                    },
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun ChapterHeaderText(chapterCount: Int?, missingChapterCount: Int) {
    Text(
        text = if (chapterCount == null) {
            stringResource(MR.strings.chapters)
        } else {
            pluralStringResource(MR.plurals.manga_num_chapters, count = chapterCount, chapterCount)
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )

    MissingChaptersWarning(missingChapterCount)
}

@Composable
private fun MissingChaptersWarning(count: Int) {
    if (count == 0) {
        return
    }

    Text(
        text = pluralStringResource(MR.plurals.missing_chapters, count = count, count),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error.copy(alpha = SECONDARY_ALPHA),
    )
}
