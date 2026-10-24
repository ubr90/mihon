package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Bookmark
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.util.selectedBackground

/**
 * Custom: a chapter (volume) shown as a cover tile, used when the source provides
 * a thumbnail for each chapter (e.g. Komga books).
 */
@Composable
fun MangaChapterGridItem(
    title: String,
    coverData: Any?,
    readProgress: String?,
    read: Boolean,
    inProgress: Boolean,
    bookmark: Boolean,
    downloaded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(4.dp),
    ) {
        Box {
            AsyncImage(
                model = coverData,
                placeholder = ColorPainter(Color(0x1F888888)),
                error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                alpha = if (read) 0.6f else 1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.extraSmall),
            )

            if (downloaded) {
                Icon(
                    imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
            if (bookmark) {
                Icon(
                    imageVector = MaterialSymbols.RoundedFilled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(22.dp),
                )
            }
        }

        // Read state bar: full when read, accent when in progress, empty otherwise
        val barColor = when {
            read -> MaterialTheme.colorScheme.primary
            inProgress -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        }
        Spacer(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(barColor),
        )

        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (read) DISABLED_ALPHA else 1f),
            modifier = Modifier.padding(top = 4.dp),
        )
        if (readProgress != null) {
            Text(
                text = readProgress,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
    }
}

private val KomgaBookRegex = Regex("""^(.*?/api/v1/books/[^/?#]+)""")

/**
 * Custom: returns the thumbnail URL of a chapter when it points to a Komga book, otherwise null.
 */
fun chapterThumbnailUrl(chapterUrl: String, baseUrl: String?): String? {
    val match = KomgaBookRegex.find(chapterUrl) ?: return null
    val bookUrl = match.groupValues[1]
    val absolute = when {
        bookUrl.startsWith("http://") || bookUrl.startsWith("https://") -> bookUrl
        baseUrl != null -> baseUrl.trimEnd('/') + "/" + bookUrl.trimStart('/')
        else -> return null
    }
    return "$absolute/thumbnail"
}
