package to.sava.legirss.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch
import to.sava.legirss.ui.platform.HorizontalResizePointerIcon
import to.sava.legirss.ui.platform.ListScrollbar
import to.sava.legirss.ui.theme.LocalLegirssStyle

@Immutable
data class TableColumn<C>(
    val id: C,
    val title: String,
    val width: Dp,
    val resizable: Boolean = true,
)

private val CELL_PADDING = 4.dp
private val RESIZE_HANDLE_WIDTH = 5.dp
private val MIN_COLUMN_WIDTH = 20.dp
private val SCROLLBAR_WIDTH = 10.dp
private const val SORT_MARK_ASCENDING = "▲"
private const val SORT_MARK_DESCENDING = "▼"

/** 行の選び方。 */
enum class RowSelectMode {
    /** その行だけを選ぶ。 */
    SINGLE,

    /** 選んでいる行からその行までの範囲に印を付ける（Shift）。 */
    RANGE,

    /** その行の印を付け外しする（Ctrl）。 */
    TOGGLE,
}

/**
 * 列見出しつきの一覧。行の高さは固定。1 行を選べるほか、複数の行に印を付けられる。
 *
 * @param selectedIndex 選んでいる行。
 * @param cursorIndex キー操作で動かす起点の行。範囲で印を付けている間は、範囲の終わりの行になる。
 * @param isMarked 印を付けた行か。印を付けた行は、選んでいる行と同じ色で示す。
 * @param onSelectAll 全ての行に印を付ける操作（Ctrl+A）をした。
 * @param onContextMenu 行を右クリックしたときに、その行と、押した位置（この一覧の左上からの距離）を伝える。
 * @param sortedColumn 並べ替えの印を出す列。
 * @param centerSelection キー操作で選択を動かしたとき、選択行を表示範囲の中央付近へ寄せる。
 * @param focusRequester この一覧にフォーカスを移すためのもの。
 */
@Composable
fun <C, R> DataTable(
    columns: List<TableColumn<C>>,
    rows: List<R>,
    rowKey: (R) -> Any,
    selectedIndex: Int?,
    cursorIndex: Int?,
    isMarked: (R) -> Boolean,
    onSelectIndex: (Int, RowSelectMode) -> Unit,
    onSelectAll: () -> Unit,
    onContextMenu: (Int, IntOffset) -> Unit,
    sortedColumn: C?,
    sortDescending: Boolean,
    onColumnClick: (C) -> Unit,
    onColumnResize: (C, Dp) -> Unit,
    centerSelection: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    cell: @Composable (row: R, column: C) -> Unit,
) {
    val style = LocalLegirssStyle.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val rowHeightPx = with(density) { style.listRowHeight.roundToPx() }
    val headerHeightPx = with(density) { style.headerHeight.roundToPx() }
    val currentRowCount by rememberUpdatedState(rows.size)
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)

    // キー操作以外で選択が表示範囲の外へ移ったときも、選択行が見えるようにする。
    LaunchedEffect(cursorIndex) {
        val laidOut = listState.layoutInfo.visibleItemsInfo.isNotEmpty()
        if (cursorIndex != null && laidOut && !listState.isFullyVisible(cursorIndex)) {
            listState.bringIntoView(cursorIndex, rowHeightPx, centerSelection)
        }
    }

    fun moveSelection(target: Int, mode: RowSelectMode) {
        if (rows.isEmpty()) return
        val index = target.coerceIn(0, rows.lastIndex)
        onSelectIndex(index, mode)
        scope.launch { listState.bringIntoView(index, rowHeightPx, centerSelection) }
    }

    BoxWithConstraints(
        modifier
            .background(style.colors.background)
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.isCtrlPressed && event.key == Key.A) {
                    onSelectAll()
                    return@onKeyEvent true
                }
                if (event.isCtrlPressed || event.isAltPressed) return@onKeyEvent false
                val current = cursorIndex ?: -1
                val page = (listState.layoutInfo.viewportSize.height / rowHeightPx - 1).coerceAtLeast(1)
                val mode = if (event.isShiftPressed) RowSelectMode.RANGE else RowSelectMode.SINGLE
                when (event.key) {
                    Key.DirectionUp -> moveSelection(current - 1, mode)
                    Key.DirectionDown -> moveSelection(current + 1, mode)
                    Key.PageUp -> moveSelection(current - page, mode)
                    Key.PageDown -> moveSelection(current + page, mode)
                    Key.MoveHome -> moveSelection(0, mode)
                    Key.MoveEnd -> moveSelection(rows.lastIndex, mode)
                    else -> return@onKeyEvent false
                }
                true
            }
            .pointerInput(rowHeightPx, headerHeightPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                        val position = event.changes.first().position
                        // 行の高さは一定なので、見出しより下で押した位置とスクロールの量から行を求める。
                        val belowHeader = position.y - headerHeightPx
                        if (belowHeader < 0) continue
                        val scrolled = listState.firstVisibleItemIndex * rowHeightPx + listState.firstVisibleItemScrollOffset
                        val index = ((belowHeader + scrolled) / rowHeightPx).toInt()
                        if (index < currentRowCount) currentOnContextMenu(index, position.round())
                    }
                }
            },
    ) {
        val contentWidth = maxOf(columns.fold(0.dp) { total, column -> total + column.width }, maxWidth - SCROLLBAR_WIDTH)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
                HeaderRow(columns, sortedColumn, sortDescending, onColumnClick, onColumnResize, Modifier.width(contentWidth))
                LazyColumn(state = listState, modifier = Modifier.width(contentWidth).weight(1f)) {
                    items(count = rows.size, key = { rowKey(rows[it]) }) { index ->
                        val highlighted = index == selectedIndex || isMarked(rows[index])
                        val background = when {
                            highlighted && focused -> style.colors.selection
                            highlighted -> style.colors.selectionInactive
                            index % 2 == 1 -> style.colors.rowAlternate
                            else -> Color.Transparent
                        }
                        Row(
                            modifier = Modifier
                                .width(contentWidth)
                                .height(style.listRowHeight)
                                .background(background)
                                .clickable(remember { MutableInteractionSource() }, indication = null) {
                                    focusRequester.requestFocus()
                                    val modifiers = windowInfo.keyboardModifiers
                                    val mode = when {
                                        modifiers.isShiftPressed -> RowSelectMode.RANGE
                                        modifiers.isCtrlPressed -> RowSelectMode.TOGGLE
                                        else -> RowSelectMode.SINGLE
                                    }
                                    onSelectIndex(index, mode)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            columns.forEach { column ->
                                Box(
                                    modifier = Modifier.width(column.width).fillMaxHeight().padding(horizontal = CELL_PADDING),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    cell(rows[index], column.id)
                                }
                            }
                        }
                    }
                }
            }
            ListScrollbar(listState, Modifier.width(SCROLLBAR_WIDTH).fillMaxHeight())
        }
    }
}

/** 一覧のセルに 1 行の文字を出す。収まらない分は省略記号にする。 */
@Composable
fun TableText(text: String, bold: Boolean = false) {
    val style = LocalLegirssStyle.current
    BasicText(
        text = text,
        style = style.textStyle(style.listFontSize, bold = bold),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun <C> HeaderRow(
    columns: List<TableColumn<C>>,
    sortedColumn: C?,
    sortDescending: Boolean,
    onColumnClick: (C) -> Unit,
    onColumnResize: (C, Dp) -> Unit,
    modifier: Modifier,
) {
    val style = LocalLegirssStyle.current
    val borderColor = style.colors.border
    Row(
        modifier
            .height(style.headerHeight)
            .background(style.colors.panel)
            .drawBehind {
                drawLine(borderColor, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
            },
    ) {
        columns.forEach { column ->
            val sortMark = when {
                column.id != sortedColumn -> ""
                sortDescending -> SORT_MARK_DESCENDING
                else -> SORT_MARK_ASCENDING
            }
            Box(
                Modifier
                    .width(column.width)
                    .fillMaxHeight()
                    .drawBehind { drawLine(borderColor, Offset(size.width - 1f, 2f), Offset(size.width - 1f, size.height - 3f)) }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onColumnClick(column.id) },
            ) {
                BasicText(
                    text = column.title + sortMark,
                    style = style.textStyle(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = CELL_PADDING),
                )
                if (column.resizable) {
                    ResizeHandle(
                        onDrag = { delta -> onColumnResize(column.id, (column.width + delta).coerceAtLeast(MIN_COLUMN_WIDTH)) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResizeHandle(onDrag: (Dp) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier
            .width(RESIZE_HANDLE_WIDTH)
            .fillMaxHeight()
            .pointerHoverIcon(HorizontalResizePointerIcon)
            .draggable(
                state = rememberDraggableState { delta -> currentOnDrag(with(density) { delta.toDp() }) },
                orientation = Orientation.Horizontal,
            ),
    )
}

/** [index] の行が表示範囲に入るようスクロールする。[center] なら範囲の中央付近へ寄せる。 */
private suspend fun LazyListState.bringIntoView(index: Int, rowHeightPx: Int, center: Boolean) {
    val viewportHeight = layoutInfo.viewportSize.height
    when {
        center -> scrollToItem(index, -(viewportHeight - rowHeightPx) / 2)
        isFullyVisible(index) -> Unit
        index <= firstVisibleItemIndex -> scrollToItem(index)
        else -> scrollToItem(index, -(viewportHeight - rowHeightPx))
    }
}

/** [index] の行が、上下とも欠けずに表示範囲に入っているか。 */
private fun LazyListState.isFullyVisible(index: Int): Boolean =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        ?.let { it.offset >= 0 && it.offset + it.size <= layoutInfo.viewportSize.height }
        ?: false
