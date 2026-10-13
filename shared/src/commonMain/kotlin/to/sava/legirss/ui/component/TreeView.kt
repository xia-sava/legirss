package to.sava.legirss.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch
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
private const val DROP_LINE_WIDTH = 2f

/** ドラッグした行を、落とした先の行のどこへ置くか。 */
enum class TreeDropPlace {
    /** 行の直前。 */
    BEFORE,

    /** 行の中。 */
    INTO,

    /** 行の直後。 */
    AFTER,
}

/** ドラッグしている行と、いま指している落とし先。 */
private data class TreeDrag<K : Any>(
    val source: K,
    val target: K? = null,
    val place: TreeDropPlace = TreeDropPlace.INTO,
)

// 中へ落とせる行で、上端と下端からこの割合までを「直前」「直後」として扱う。
private const val EDGE_FRACTION = 0.25f
private const val HALF = 0.5f

/**
 * @param onSelect 行をクリックして選んだ。
 * @param onKeySelect キー操作で選択を別の行へ動かした。
 * @param onContextMenu 行を右クリックしたときに、その行と、押した位置（このツリーの左上からの距離）を伝える。
 * @param onMove 行をドラッグして別の行へ落としたときに、動かした行、落とした先の行、置く場所を伝える。
 * @param onDelete 行を選んだ状態で Delete を押した。
 * @param focusRequester このツリーにフォーカスを移すためのもの。
 */
@Composable
fun <K : Any> TreeView(
    rows: List<TreeRow<K>>,
    selectedKey: K?,
    onSelect: (K) -> Unit,
    onKeySelect: (K) -> Unit,
    onToggle: (K) -> Unit,
    onContextMenu: (K, IntOffset) -> Unit,
    onMove: (source: K, target: K, place: TreeDropPlace) -> Unit,
    onDelete: (K) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val style = LocalLegirssStyle.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentRows by rememberUpdatedState(rows)
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)
    val currentOnMove by rememberUpdatedState(onMove)
    var drag by remember { mutableStateOf<TreeDrag<K>?>(null) }
    var focused by remember { mutableStateOf(false) }
    val rowHeight = style.treeRowHeight
    val selectedIndex = rows.indexOfFirst { it.key == selectedKey }

    // キー操作で選択が表示範囲の外へ移ったら、選択した行が見えるように送る。
    LaunchedEffect(selectedIndex) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == selectedIndex && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportSize.height }
        if (focused && selectedIndex >= 0 && visible.isNotEmpty() && !fullyVisible) listState.scrollToItem(selectedIndex)
    }

    fun handleKey(key: Key): Boolean {
        val row = rows.getOrNull(selectedIndex)
        fun selectAt(index: Int) = rows.getOrNull(index.coerceIn(0, rows.lastIndex))?.let { onKeySelect(it.key) }
        when (key) {
            Key.DirectionUp -> selectAt(selectedIndex - 1)
            Key.DirectionDown -> selectAt(selectedIndex + 1)
            Key.MoveHome -> selectAt(0)
            Key.MoveEnd -> selectAt(rows.lastIndex)
            // 開いていれば閉じ、閉じていれば親の行へ移る。
            Key.DirectionLeft -> when {
                row == null -> Unit
                row.expandable && row.expanded -> onToggle(row.key)
                else -> rows.take(selectedIndex).lastOrNull { it.depth < row.depth }?.let { onKeySelect(it.key) }
            }
            Key.DirectionRight -> row?.takeIf { it.expandable && !it.expanded }?.let { onToggle(it.key) }
            Key.Delete -> row?.let { onDelete(it.key) }
            else -> return false
        }
        return true
    }

    // 行の高さは一定なので、押した位置とスクロールの量から行を求める。戻り値は、行の番号と、行の中での上端からの割合。
    fun rowAt(y: Float, rowHeightPx: Int): Pair<Int, Float> {
        val fromTop = y + listState.firstVisibleItemIndex * rowHeightPx + listState.firstVisibleItemScrollOffset
        val index = (fromTop / rowHeightPx).toInt()
        return index to (fromTop - index * rowHeightPx) / rowHeightPx
    }

    val contextMenuDetector = Modifier.pointerInput(rowHeight) {
        val rowHeightPx = rowHeight.roundToPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                val position = event.changes.first().position
                currentRows.getOrNull(rowAt(position.y, rowHeightPx).first)?.let { currentOnContextMenu(it.key, position.round()) }
            }
        }
    }
    val dragDetector = Modifier.pointerInput(rowHeight) {
        val rowHeightPx = rowHeight.roundToPx()
        detectDragGestures(
            onDragStart = { start -> drag = currentRows.getOrNull(rowAt(start.y, rowHeightPx).first)?.let { TreeDrag(it.key) } },
            onDrag = { change, _ ->
                val y = change.position.y
                val (index, fraction) = rowAt(y, rowHeightPx)
                val row = currentRows.getOrNull(index)
                drag = drag?.copy(target = row?.key, place = row?.let { dropPlaceOf(it, fraction) } ?: TreeDropPlace.INTO)
                // 端の近くまで運んだら、その先の行が見えるように送る。
                when {
                    y < rowHeightPx -> scope.launch { listState.scrollBy(-rowHeightPx.toFloat()) }
                    y > size.height - rowHeightPx -> scope.launch { listState.scrollBy(rowHeightPx.toFloat()) }
                }
            },
            onDragEnd = {
                drag?.let { finished -> finished.target?.takeIf { it != finished.source }?.let { currentOnMove(finished.source, it, finished.place) } }
                drag = null
            },
            onDragCancel = { drag = null },
        )
    }
    Box(
        modifier
            .background(style.colors.background)
            .onFocusChanged { focused = it.hasFocus }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && rows.isNotEmpty() && handleKey(event.key) }
            .then(contextMenuDetector)
            .then(dragDetector),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.key }) { row ->
                TreeRowView(
                    row = row,
                    selected = row.key == selectedKey,
                    focused = focused,
                    dropPlace = drag?.takeIf { it.target == row.key && it.source != row.key }?.place,
                    onSelect = onSelect,
                    onToggle = onToggle,
                )
            }
        }
        ListScrollbar(listState, Modifier.align(Alignment.CenterEnd).width(SCROLLBAR_WIDTH).fillMaxHeight())
    }
}

/** 中へほかの行を入れられる行なら上下の端だけを「直前」「直後」とし、入れられない行なら上半分と下半分で分ける。 */
private fun dropPlaceOf(row: TreeRow<*>, fraction: Float): TreeDropPlace {
    val container = row.icon == TreeIcon.ALL || row.icon == TreeIcon.GROUP
    return when {
        container && fraction < EDGE_FRACTION -> TreeDropPlace.BEFORE
        container && fraction > 1f - EDGE_FRACTION -> TreeDropPlace.AFTER
        container -> TreeDropPlace.INTO
        fraction < HALF -> TreeDropPlace.BEFORE
        else -> TreeDropPlace.AFTER
    }
}

/**
 * @param dropPlace ドラッグしている行をこの行へ落とすと置かれる場所。落とし先でなければ null。
 */
@Composable
private fun <K : Any> TreeRowView(
    row: TreeRow<K>,
    selected: Boolean,
    focused: Boolean,
    dropPlace: TreeDropPlace?,
    onSelect: (K) -> Unit,
    onToggle: (K) -> Unit,
) {
    val style = LocalLegirssStyle.current
    val colors = style.colors
    val background = when {
        dropPlace == TreeDropPlace.INTO -> colors.hover
        selected && focused -> colors.selection
        selected -> colors.selectionInactive
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(style.treeRowHeight)
            .background(background)
            .drawBehind {
                // 落とすと行の間に入るときは、入る位置に線を引く。
                val lineY = when (dropPlace) {
                    TreeDropPlace.BEFORE -> DROP_LINE_WIDTH / 2f
                    TreeDropPlace.AFTER -> size.height - DROP_LINE_WIDTH / 2f
                    else -> return@drawBehind
                }
                drawLine(colors.iconUnread, Offset(0f, lineY), Offset(size.width, lineY), DROP_LINE_WIDTH)
            }
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
