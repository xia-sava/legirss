package to.sava.legirss.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.sava.legirss.ui.platform.ListScrollbar
import to.sava.legirss.ui.theme.LegirssColors
import to.sava.legirss.ui.theme.LocalLegirssStyle

enum class TreeIcon {
    ALL,
    GROUP,
    CHANNEL,
    CHANNEL_PAUSED,
    CHANNEL_ERROR,
}

/** ツリーに表示する 1 行。開いているノードの子だけを、表示順に平らに並べて渡す。 */
@Immutable
data class TreeRow<K : Any>(
    val key: K,
    val depth: Int,
    val label: String,
    val bold: Boolean,
    val icon: TreeIcon,
    val expandable: Boolean,
    val expanded: Boolean,
)

private val ICON_SIZE = 12.dp
private val TOGGLE_SIZE = 14.dp
private val ICON_TEXT_GAP = 4.dp
private val SCROLLBAR_WIDTH = 10.dp

@Composable
fun <K : Any> TreeView(
    rows: List<TreeRow<K>>,
    selectedKey: K?,
    onSelect: (K) -> Unit,
    onToggle: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalLegirssStyle.current
    val listState = rememberLazyListState()
    Box(modifier.background(style.colors.background)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.key }) { row ->
                TreeRowView(row, selected = row.key == selectedKey, onSelect = onSelect, onToggle = onToggle)
            }
        }
        ListScrollbar(listState, Modifier.align(Alignment.CenterEnd).width(SCROLLBAR_WIDTH).fillMaxHeight())
    }
}

@Composable
private fun <K : Any> TreeRowView(
    row: TreeRow<K>,
    selected: Boolean,
    onSelect: (K) -> Unit,
    onToggle: (K) -> Unit,
) {
    val style = LocalLegirssStyle.current
    val colors = style.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(style.treeRowHeight)
            .background(if (selected) colors.selection else Color.Transparent)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(row.key) }
            .padding(start = style.treeIndent * row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(TOGGLE_SIZE)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = row.expandable) {
                    onToggle(row.key)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (row.expandable) {
                Canvas(Modifier.size(TOGGLE_SIZE)) { drawToggle(row.expanded, colors.textDim) }
            }
        }
        Canvas(Modifier.size(ICON_SIZE)) { drawTreeIcon(row.icon, colors) }
        Spacer(Modifier.width(ICON_TEXT_GAP))
        BasicText(
            text = row.label,
            style = style.textStyle(style.treeFontSize, bold = row.bold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/** 開閉の三角。開いていれば下向き、閉じていれば右向き。 */
private fun DrawScope.drawToggle(expanded: Boolean, color: Color) {
    val unit = size.minDimension / 14f
    val path = Path().apply {
        if (expanded) {
            moveTo(3f * unit, 5f * unit)
            lineTo(11f * unit, 5f * unit)
            lineTo(7f * unit, 10f * unit)
        } else {
            moveTo(5f * unit, 3f * unit)
            lineTo(10f * unit, 7f * unit)
            lineTo(5f * unit, 11f * unit)
        }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawTreeIcon(icon: TreeIcon, colors: LegirssColors) {
    val unit = size.minDimension / 12f
    when (icon) {
        TreeIcon.ALL -> (0..2).forEach { line ->
            drawRect(colors.iconUnread, Offset(1f * unit, (2f + 3.5f * line) * unit), Size(10f * unit, 1.5f * unit))
        }

        TreeIcon.GROUP -> {
            drawRect(colors.iconGroup, Offset(1f * unit, 2f * unit), Size(4.5f * unit, 2f * unit))
            drawRect(colors.iconGroup, Offset(1f * unit, 3.5f * unit), Size(10f * unit, 7f * unit))
        }

        TreeIcon.CHANNEL -> drawChannel(colors.iconChannel, unit)
        TreeIcon.CHANNEL_PAUSED -> drawChannel(colors.iconPaused, unit)
        TreeIcon.CHANNEL_ERROR -> drawChannel(colors.iconError, unit)
    }
}

private fun DrawScope.drawChannel(color: Color, unit: Float) {
    drawRoundRect(color, Offset(1.5f * unit, 1.5f * unit), Size(9f * unit, 9f * unit), CornerRadius(2f * unit))
}
