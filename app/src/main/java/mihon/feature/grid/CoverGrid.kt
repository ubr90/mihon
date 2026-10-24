package mihon.feature.grid

import android.app.Application
import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import uy.kohesive.injekt.injectLazy

/**
 * Custom: shared look of the cover grids (NAS browser and the "grid only" chapter view).
 */
object CoverGridSettings {
    const val MODE_2_3 = 0
    const val MODE_A4 = 1
    const val MODE_AUTO = 2
    const val MODE_AUTO_ROW = 3
    const val MODE_JUSTIFIED = 4

    /** Internal: square tiles, used when a screen has no thumbnails at all. */
    const val MODE_SQUARE = 100

    // Name display: show / hide / hide [bracket] parts (folders only)
    const val NAME_SHOW = 0
    const val NAME_HIDE = 1
    const val NAME_NO_BRACKETS = 2

    const val RATIO_2_3 = 2f / 3f
    const val RATIO_A4 = 1f / 1.414f

    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 12

    private val app: Application by injectLazy()
    private val prefs by lazy { app.getSharedPreferences("cover_grid", Context.MODE_PRIVATE) }

    val aspectMode by lazy { MutableStateFlow(prefs.getInt("aspect", MODE_2_3)) }
    val spacing by lazy { MutableStateFlow(prefs.getInt("spacing", 8)) }
    val nameMode by lazy { MutableStateFlow(prefs.getInt("name_mode", NAME_SHOW)) }
    val rowHeight by lazy { MutableStateFlow(prefs.getInt("row_height", 200)) }
    val nasColumns by lazy { MutableStateFlow(prefs.getInt("nas_columns", 0)) }
    val chapterColumns by lazy { MutableStateFlow(prefs.getInt("chapter_columns", 0)) }

    fun setAspectMode(v: Int) { aspectMode.value = v; prefs.edit { putInt("aspect", v) } }
    fun setSpacing(v: Int) { spacing.value = v; prefs.edit { putInt("spacing", v) } }
    fun setNameMode(v: Int) { nameMode.value = v; prefs.edit { putInt("name_mode", v) } }
    fun cycleNameMode() = setNameMode((nameMode.value + 1) % 3)

    private val bracketRegex = Regex("""\s*\[[^\]]*\]\s*""")

    /** Removes [bracket] parts from a name; keeps the original when nothing would be left. */
    fun stripBrackets(name: String): String =
        name.replace(bracketRegex, " ").replace(Regex("""\s+"""), " ").trim().ifEmpty { name }
    fun setRowHeight(v: Int) { rowHeight.value = v; prefs.edit { putInt("row_height", v) } }
    fun setNasColumns(v: Int) { nasColumns.value = v; prefs.edit { putInt("nas_columns", v) } }
    fun setChapterColumns(v: Int) { chapterColumns.value = v; prefs.edit { putInt("chapter_columns", v) } }

    /** Saved column count, or a default based on the width when never set. */
    fun columnsOrDefault(saved: Int, widthDp: Float): Int =
        if (saved >= MIN_COLUMNS) saved else (widthDp / 130f).toInt().coerceIn(3, 8)

    /**
     * Pinch step: spread = bigger tiles, pinch = smaller tiles.
     * Returns the new column count / row height.
     */
    fun stepColumns(current: Int, bigger: Boolean) =
        (if (bigger) current - 1 else current + 1).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun stepRowHeight(current: Int, bigger: Boolean) =
        (if (bigger) current * 1.25f else current / 1.25f).toInt().coerceIn(80, 600)

    fun modeName(mode: Int) = when (mode) {
        MODE_2_3 -> "2:3 (기본)"
        MODE_A4 -> "A4"
        MODE_AUTO -> "자동"
        MODE_AUTO_ROW -> "자동 (세로길이맞춤)"
        else -> "높이 통일 · 너비 자유"
    }
}

/** Known image aspect ratios (width / height) of thumbnails that were loaded, by key. */
object CoverAspectCache {
    val ratios = mutableStateMapOf<String, Float>()
}

/**
 * Detects a two-finger pinch and reports steps without blocking normal scrolling.
 * [onStep] gets true when the fingers spread apart (bigger tiles).
 */
fun Modifier.pinchSteps(key: Any?, onStep: (bigger: Boolean) -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var base = 0f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val d = (pressed[0].position - pressed[1].position).getDistance()
                if (base == 0f) {
                    base = d
                } else if (d / base > 1.3f) {
                    onStep(true)
                    base = d
                } else if (d / base < 0.77f) {
                    onStep(false)
                    base = d
                }
                event.changes.forEach { it.consume() }
            } else {
                base = 0f
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Tile renderer: [modifier] sets the tile width, [boxAspect] is the image box ratio (w/h),
 * [crop] tells whether the image should fill the box (fixed ratio modes) or fit it.
 */
typealias CoverTile<T> = @Composable (item: T, modifier: Modifier, boxAspect: Float, crop: Boolean) -> Unit

/**
 * Adds cover tiles to a LazyColumn in the chosen [mode].
 * [aspectOf] returns the real image ratio when known.
 */
fun <T> LazyListScope.coverGridItems(
    items: List<T>,
    key: (T) -> Any,
    aspectOf: (T) -> Float?,
    mode: Int,
    columns: Int,
    spacing: Dp,
    rowHeight: Dp,
    contentWidth: Dp,
    horizontalPadding: Dp,
    tile: CoverTile<T>,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = horizontalPadding)
        .padding(bottom = spacing)
    val fixed = when (mode) {
        CoverGridSettings.MODE_2_3 -> CoverGridSettings.RATIO_2_3
        CoverGridSettings.MODE_A4 -> CoverGridSettings.RATIO_A4
        CoverGridSettings.MODE_SQUARE -> 1f
        else -> null
    }
    fun ratio(item: T) = fixed ?: aspectOf(item) ?: CoverGridSettings.RATIO_A4

    when (mode) {
        CoverGridSettings.MODE_AUTO -> {
            // Masonry: each tile goes to the currently shortest column (not lazy, fine for folders/volumes)
            item(key = "cover-grid-masonry") {
                val cols = List(columns) { mutableListOf<T>() }
                val heights = FloatArray(columns)
                items.forEach { item ->
                    val c = heights.indices.minBy { heights[it] }
                    cols[c] += item
                    heights[c] += 1f / ratio(item) + 0.25f
                }
                Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    cols.forEach { col ->
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(spacing),
                        ) {
                            col.forEach { tile(it, Modifier.fillMaxWidth(), ratio(it), false) }
                        }
                    }
                }
            }
        }
        CoverGridSettings.MODE_JUSTIFIED -> {
            val rows = justifiedRows(
                ratios = items.map { ratio(it) },
                width = (contentWidth - horizontalPadding * 2).value,
                targetHeight = rowHeight.value,
                spacing = spacing.value,
            )
            items(rows, key = { "cover-row-" + key(items[it.first.first]) }) { (range, height) ->
                Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    range.forEach { i ->
                        val item = items[i]
                        val r = ratio(item)
                        tile(item, Modifier.width((height * r).dp), r, false)
                    }
                }
            }
        }
        else -> {
            // Fixed ratio or auto (row height = tallest tile in the row)
            val rows = items.chunked(columns)
            items(rows, key = { "cover-row-" + key(it.first()) }) { row ->
                Row(
                    modifier = rowModifier,
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.Top,
                ) {
                    row.forEach { item -> tile(item, Modifier.weight(1f), ratio(item), fixed != null) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Splits items into rows of equal height that fill [width] (Google Photos style).
 * Returns index ranges with the row height to use.
 */
fun justifiedRows(ratios: List<Float>, width: Float, targetHeight: Float, spacing: Float): List<Pair<IntRange, Float>> {
    val result = mutableListOf<Pair<IntRange, Float>>()
    var start = 0
    var prevHeight = targetHeight
    while (start < ratios.size) {
        var end = start
        var sum = 0f
        while (end < ratios.size) {
            val next = sum + ratios[end]
            val rowWidth = next * targetHeight + spacing * (end - start)
            if (rowWidth > width && end > start) break
            sum = next
            end++
        }
        val gaps = spacing * (end - start - 1)
        val fitHeight = (width - gaps) / sum
        // Last row uses the previous row's height (not stretched), unless that would overflow
        val height = if (end >= ratios.size && fitHeight > prevHeight) prevHeight else fitHeight
        result += (start until end) to height
        prevHeight = height
        start = end
    }
    return result
}

@Composable
fun CoverGridOptionsDialog(onDismiss: () -> Unit) {
    val mode by CoverGridSettings.aspectMode.collectAsState()
    val spacing by CoverGridSettings.spacing.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("그리드 보기") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("썸네일 비율", style = MaterialTheme.typography.titleSmall)
                listOf(
                    CoverGridSettings.MODE_2_3,
                    CoverGridSettings.MODE_A4,
                    CoverGridSettings.MODE_AUTO,
                    CoverGridSettings.MODE_AUTO_ROW,
                    CoverGridSettings.MODE_JUSTIFIED,
                ).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == m, onClick = { CoverGridSettings.setAspectMode(m) })
                        Text(CoverGridSettings.modeName(m))
                    }
                }
                Text(
                    "간격: ${spacing}dp",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = spacing.toFloat(),
                    onValueChange = { CoverGridSettings.setSpacing(it.toInt()) },
                    valueRange = 0f..24f,
                    steps = 23,
                )
                Text(
                    "두 손가락으로 오므리면 작게, 벌리면 크게 볼 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
