package to.sava.legirss.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.ui.component.DataTable
import to.sava.legirss.ui.component.HorizontalSplit
import to.sava.legirss.ui.component.HtmlContent
import to.sava.legirss.ui.component.MenuEntry
import to.sava.legirss.ui.component.PopupMenu
import to.sava.legirss.ui.component.RowSelectMode
import to.sava.legirss.ui.component.SegmentedButtons
import to.sava.legirss.ui.component.TableColumn
import to.sava.legirss.ui.component.TableText
import to.sava.legirss.ui.component.ToolbarButton
import to.sava.legirss.ui.component.TreeIcon
import to.sava.legirss.ui.component.TreeRow
import to.sava.legirss.ui.component.TreeView
import to.sava.legirss.ui.component.VerticalSplit
import to.sava.legirss.ui.platform.ContentScrollbar
import to.sava.legirss.ui.platform.formatListDateTime
import to.sava.legirss.ui.theme.LegirssColors
import to.sava.legirss.ui.theme.LocalLegirssStyle
import to.sava.legirss.update.UpdateProgress

private val BAR_PADDING = 4.dp
private val TOOLBAR_GROUP_GAP = 8.dp
private val STATE_ICON_SIZE = 9.dp
private val ARTICLE_PANE_PADDING = 8.dp
private val SCROLLBAR_WIDTH = 10.dp
private val ARTICLE_TITLE_EXTRA_SIZE = 3.sp
private val ARTICLE_LINE_SCROLL = 40.dp

// PageUp と PageDown で送る量。読んでいた位置を見失わないよう、表示範囲より少し短くする。
private const val ARTICLE_PAGE_FRACTION = 0.85f

private const val UPDATE_ALL_LABEL = "更新"
private const val UPDATE_SELECTED_LABEL = "選択更新"
private const val CANCEL_UPDATE_LABEL = "中止"
private const val ADD_CHANNEL_LABEL = "追加"
private const val SETTINGS_LABEL = "設定"

private const val UPDATE_NODE_LABEL = "更新"
private const val PAUSE_LABEL = "購読中止"
private const val RESUME_LABEL = "購読再開"
private const val ADD_CHANNEL_MENU_LABEL = "チャンネルの追加…"
private const val ADD_GROUP_MENU_LABEL = "グループの追加…"
private const val RENAME_MENU_LABEL = "名前の変更…"
private const val CHANGE_FEED_URL_MENU_LABEL = "フィードの URL の変更…"
private const val DELETE_MENU_LABEL = "削除…"
private const val SORT_BY_NAME_MENU_LABEL = "中を名前の順に並べ直す"
private const val IMPORT_OPML_MENU_LABEL = "OPML の読み込み…"
private const val EXPORT_OPML_MENU_LABEL = "OPML の書き出し…"

private const val OPEN_IN_BROWSER_LABEL = "ブラウザで開く"
private const val MARK_UNREAD_LABEL = "未読にする"
private const val MARK_READ_LABEL = "既読にする"
private const val COPY_URL_LABEL = "URL をコピー"
private const val COPY_TITLE_LABEL = "タイトルをコピー"
private const val COPY_TITLE_AND_URL_LABEL = "タイトルと URL をコピー"
private const val DELETE_ARTICLE_LABEL = "削除"

/** ツールバーの右端に出す通知。[onClick] があれば、押せるボタンとして出す。 */
@Immutable
data class ToolbarNotice(
    val label: String,
    val onClick: (() -> Unit)?,
)

/**
 * @param toolbarNotice ツールバーの右端に出す通知。出さなければ null。
 * @param articleScroll 記事の表示領域のスクロール位置。キー操作で本文を送るために、呼び出し側と共有する。
 * @param layout 境目の位置と列の幅。
 * @param onLayoutChange 利用者が境目や列の幅を動かしたときに、動かした後の値を伝える。
 */
@Composable
fun MainScreen(
    state: MainUiState,
    actions: MainActions,
    articleScroll: ScrollState,
    layout: ScreenLayout,
    onLayoutChange: (ScreenLayout) -> Unit,
    modifier: Modifier = Modifier,
    toolbarNotice: ToolbarNotice? = null,
) {
    val style = LocalLegirssStyle.current
    Box(modifier.fillMaxSize()) {
        MainContent(state, actions, articleScroll, layout, onLayoutChange, toolbarNotice)
        state.dialog?.let { DialogHost(it, state.settings, actions) }
    }
}

@Composable
private fun MainContent(
    state: MainUiState,
    actions: MainActions,
    articleScroll: ScrollState,
    layout: ScreenLayout,
    onLayoutChange: (ScreenLayout) -> Unit,
    toolbarNotice: ToolbarNotice?,
) {
    val style = LocalLegirssStyle.current
    val panes = remember { PaneFocus() }
    Column(
        Modifier
            .fillMaxSize()
            .background(style.colors.panel)
            .onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.F6).also { if (it) panes.moveOn(forward = !event.isShiftPressed) }
            },
    ) {
        Toolbar(state, actions, toolbarNotice)
        HorizontalSplit(
            firstWidth = layout.treeWidth,
            onFirstWidthChange = { onLayoutChange(layout.copy(treeWidth = it)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            first = { TreePane(state, actions, panes) },
            second = {
                VerticalSplit(
                    firstHeight = layout.listHeight,
                    onFirstHeightChange = { onLayoutChange(layout.copy(listHeight = it)) },
                    modifier = Modifier.fillMaxSize(),
                    first = {
                        ArticleTable(
                            state = state,
                            actions = actions,
                            columnWidths = layout.columnWidths,
                            onColumnResize = { column, width -> onLayoutChange(layout.withColumnWidth(column, width)) },
                            panes = panes,
                        )
                    },
                    second = { ArticlePane(state, actions, articleScroll, panes) },
                )
            },
        )
        StatusBar(state)
    }
}

/** フォーカスを置ける領域。F6 でこの順にたどる。 */
private enum class Pane {
    TREE,
    LIST,
    ARTICLE,
}

/** どの領域にフォーカスがあるかを覚え、領域の間でフォーカスを移す。 */
@Stable
private class PaneFocus {
    private val requesters = Pane.entries.associateWith { FocusRequester() }
    private var current = Pane.LIST

    fun requesterOf(pane: Pane): FocusRequester = requesters.getValue(pane)

    fun focus(pane: Pane) {
        requesterOf(pane).requestFocus()
    }

    /** [pane] の領域に付けて、そこへフォーカスが入ったことを覚える。 */
    fun track(pane: Pane): Modifier = Modifier.onFocusChanged { if (it.hasFocus) current = pane }

    /** いまの領域の次（[forward] が false なら前）の領域へフォーカスを移す。 */
    fun moveOn(forward: Boolean) {
        focus(Pane.entries[(current.ordinal + if (forward) 1 else -1).mod(Pane.entries.size)])
    }
}

/** ツリーと、行を右クリックしたときのメニュー。 */
@Composable
private fun TreePane(state: MainUiState, actions: MainActions, panes: PaneFocus) {
    // メニューを出している行と、出す位置。出していなければ null。
    var menu by remember { mutableStateOf<Pair<NodeSelection, IntOffset>?>(null) }
    Box(Modifier.fillMaxSize().then(panes.track(Pane.TREE))) {
        TreeView(
            rows = state.treeRows,
            selectedKey = state.selectedNode,
            // クリックで選んだあとは、キーですぐ読み始められるようフォーカスを一覧に置く。
            onSelect = { key ->
                actions.selectNode(key)
                panes.focus(Pane.LIST)
            },
            onKeySelect = actions::selectNode,
            onToggle = actions::toggleNode,
            onContextMenu = { key, position ->
                actions.selectNode(key)
                menu = key to position
            },
            onMove = actions::moveNode,
            onDelete = actions::requestDelete,
            modifier = Modifier.fillMaxSize(),
            focusRequester = panes.requesterOf(Pane.TREE),
        )
        menu?.let { (key, position) ->
            state.treeRows.firstOrNull { it.key == key }?.let { row ->
                PopupMenu(treeMenuEntries(row, actions), position, onDismiss = { menu = null })
            }
        }
    }
}

private fun treeMenuEntries(row: TreeRow<NodeSelection>, actions: MainActions): List<MenuEntry?> = buildList {
    add(MenuEntry(UPDATE_NODE_LABEL) { actions.updateNode(row.key) })
    when (row.icon) {
        TreeIcon.ALL -> Unit
        TreeIcon.GROUP -> {
            add(MenuEntry(PAUSE_LABEL) { actions.setNodePaused(row.key, paused = true) })
            add(MenuEntry(RESUME_LABEL) { actions.setNodePaused(row.key, paused = false) })
        }
        TreeIcon.CHANNEL_PAUSED -> add(MenuEntry(RESUME_LABEL) { actions.setNodePaused(row.key, paused = false) })
        TreeIcon.CHANNEL, TreeIcon.CHANNEL_ERROR, TreeIcon.CHANNEL_UPDATING -> add(MenuEntry(PAUSE_LABEL) { actions.setNodePaused(row.key, paused = true) })
    }
    add(null)
    add(MenuEntry(ADD_CHANNEL_MENU_LABEL, onClick = actions::openAddChannel))
    add(MenuEntry(ADD_GROUP_MENU_LABEL, onClick = actions::openAddGroup))
    val isChannel = row.icon != TreeIcon.ALL && row.icon != TreeIcon.GROUP
    if (row.icon != TreeIcon.ALL) {
        add(null)
        add(MenuEntry(RENAME_MENU_LABEL) { actions.openRename(row.key) })
        if (isChannel) add(MenuEntry(CHANGE_FEED_URL_MENU_LABEL) { actions.openChangeFeedUrl(row.key) })
        add(MenuEntry(DELETE_MENU_LABEL) { actions.requestDelete(row.key) })
    }
    if (!isChannel) {
        add(null)
        add(MenuEntry(SORT_BY_NAME_MENU_LABEL) { actions.sortChildrenByName(row.key) })
        add(null)
        if (row.icon == TreeIcon.ALL) add(MenuEntry(IMPORT_OPML_MENU_LABEL, onClick = actions::importOpml))
        add(MenuEntry(EXPORT_OPML_MENU_LABEL) { actions.exportOpml(row.key) })
    }
}

@Composable
private fun Toolbar(state: MainUiState, actions: MainActions, notice: ToolbarNotice?) {
    val style = LocalLegirssStyle.current
    val updating = state.updateProgress != null
    Row(
        modifier = Modifier.fillMaxWidth().height(style.toolbarHeight).padding(horizontal = BAR_PADDING, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(if (updating) CANCEL_UPDATE_LABEL else UPDATE_ALL_LABEL, onClick = actions::updateAll)
        if (!updating) ToolbarButton(UPDATE_SELECTED_LABEL, onClick = actions::updateSelected)
        ToolbarButton(ADD_CHANNEL_LABEL, onClick = actions::openAddChannel)
        Spacer(Modifier.width(TOOLBAR_GROUP_GAP))
        SegmentedButtons(
            options = StateFilter.entries,
            selected = state.filter,
            label = StateFilter::label,
            onSelect = actions::setFilter,
            modifier = Modifier.fillMaxHeight(),
        )
        Spacer(Modifier.width(TOOLBAR_GROUP_GAP))
        ToolbarButton(SETTINGS_LABEL, onClick = actions::openSettings)
        Box(Modifier.weight(1f))
        notice?.let {
            when (val onClick = it.onClick) {
                null -> BasicText(it.label, style = style.textStyle(color = style.colors.textDim))
                else -> ToolbarButton(it.label, onClick = onClick)
            }
        }
    }
}

@Composable
private fun ArticleTable(
    state: MainUiState,
    actions: MainActions,
    columnWidths: Map<ArticleColumn, Dp>,
    onColumnResize: (ArticleColumn, Dp) -> Unit,
    panes: PaneFocus,
) {
    val focusRequester = panes.requesterOf(Pane.LIST)

    // 起動した直後と、しまっていたウィンドウを出した直後に、キーですぐ読み始められるようフォーカスを一覧に置く。
    // 一覧が画面に組み込まれた後に実行されるよう、一覧と同じ場所で行う。
    LaunchedEffect(state.listFocusRequest) { focusRequester.requestFocus() }

    // 右クリックのメニューを出す位置。出していなければ null。
    var menuPosition by remember { mutableStateOf<IntOffset?>(null) }
    Box(Modifier.fillMaxSize()) {
        ArticleDataTable(
            state = state,
            actions = actions,
            columnWidths = columnWidths,
            onColumnResize = onColumnResize,
            onContextMenu = { index, position ->
                // 操作の対象になっていない行で開いたら、その行を選んでから出す。
                if (state.articles.getOrNull(index) !in state.targetArticles) actions.selectArticle(index)
                menuPosition = position
            },
            modifier = Modifier.fillMaxSize().then(panes.track(Pane.LIST)),
            focusRequester = focusRequester,
        )
        menuPosition?.let { position ->
            PopupMenu(articleMenuEntries(actions), position, onDismiss = { menuPosition = null })
        }
    }
}

private fun articleMenuEntries(actions: MainActions): List<MenuEntry?> = listOf(
    MenuEntry(OPEN_IN_BROWSER_LABEL, onClick = actions::openSelectedArticle),
    null,
    MenuEntry(MARK_UNREAD_LABEL) { actions.setSelectedState(ArticleState.UNREAD) },
    MenuEntry(MARK_READ_LABEL) { actions.setSelectedState(ArticleState.READ) },
    null,
    MenuEntry(COPY_URL_LABEL) { actions.copySelected(CopyContent.URL) },
    MenuEntry(COPY_TITLE_LABEL) { actions.copySelected(CopyContent.TITLE) },
    MenuEntry(COPY_TITLE_AND_URL_LABEL) { actions.copySelected(CopyContent.TITLE_AND_URL) },
    null,
    MenuEntry(DELETE_ARTICLE_LABEL, onClick = actions::deleteSelectedArticles),
)

@Composable
private fun ArticleDataTable(
    state: MainUiState,
    actions: MainActions,
    columnWidths: Map<ArticleColumn, Dp>,
    onColumnResize: (ArticleColumn, Dp) -> Unit,
    onContextMenu: (Int, IntOffset) -> Unit,
    modifier: Modifier,
    focusRequester: FocusRequester,
) {
    val colors = LocalLegirssStyle.current.colors
    DataTable(
        columns = ArticleColumn.entries.map { column ->
            TableColumn(
                id = column,
                title = column.title,
                width = columnWidths.getValue(column),
                resizable = column != ArticleColumn.STATE,
            )
        },
        rows = state.articles,
        rowKey = ArticleRow::id,
        selectedIndex = state.selectedArticleIndex,
        cursorIndex = state.markRangeEndIndex ?: state.selectedArticleIndex,
        isMarked = { it.id in state.markedArticleIds },
        onSelectIndex = { index, mode ->
            when (mode) {
                RowSelectMode.SINGLE -> actions.selectArticle(index)
                RowSelectMode.RANGE -> actions.markArticlesTo(index)
                RowSelectMode.TOGGLE -> actions.toggleArticleMark(index)
            }
        },
        onSelectAll = actions::markAllArticles,
        onContextMenu = onContextMenu,
        scrollResetKey = state.listArrangement,
        // 並べ直したら、読み始める記事（一番古い未読）が見える位置へ移す。未読が無ければ先頭へ戻す。
        scrollResetIndex = state.lastUnreadIndex ?: 0,
        sortedColumn = state.sort.column,
        sortDescending = state.sort.descending,
        onColumnClick = actions::sortBy,
        onColumnResize = onColumnResize,
        centerSelection = state.settings.centerSelection,
        modifier = modifier,
        focusRequester = focusRequester,
    ) { article, column ->
        val unread = article.state == ArticleState.UNREAD
        when (column) {
            ArticleColumn.STATE -> Canvas(Modifier.size(STATE_ICON_SIZE)) { drawStateIcon(article.state, colors) }
            ArticleColumn.DATE -> TableText(formatListDateTime(article.sortAt), unread)
            ArticleColumn.TITLE -> TableText(article.title, unread)
            ArticleColumn.CHANNEL -> TableText(state.channelNames[article.channelId].orEmpty(), unread)
            ArticleColumn.CATEGORY -> TableText(article.category.orEmpty(), unread)
            ArticleColumn.AUTHOR -> TableText(article.author.orEmpty(), unread)
        }
    }
}

/** 未読は塗った丸、既読は輪郭だけの丸で表す。 */
private fun DrawScope.drawStateIcon(state: ArticleState, colors: LegirssColors) {
    when (state) {
        ArticleState.UNREAD -> drawCircle(colors.iconUnread)
        ArticleState.READ -> drawCircle(colors.iconPaused, radius = size.minDimension / 2f - 1f, style = Stroke(1f))
    }
}

/**
 * 選んでいる記事の、タイトル・日付とチャンネル名・本文を上から順に出す。
 *
 * フォーカスがあるときは、↑↓ PageUp PageDown Home End で本文を送れる。
 */
@Composable
private fun ArticlePane(state: MainUiState, actions: MainActions, scroll: ScrollState, panes: PaneFocus) {
    val style = LocalLegirssStyle.current
    val article = state.selectedArticle
    val scope = rememberCoroutineScope()
    val lineScrollPx = with(LocalDensity.current) { ARTICLE_LINE_SCROLL.toPx() }

    // 別の記事を選んだら、先頭から読めるようにスクロール位置を戻す。
    LaunchedEffect(article?.id) { scroll.scrollTo(0) }

    Row(
        Modifier
            .fillMaxSize()
            .background(style.colors.background)
            .then(panes.track(Pane.ARTICLE))
            .focusRequester(panes.requesterOf(Pane.ARTICLE))
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionUp -> -lineScrollPx
                    Key.DirectionDown -> lineScrollPx
                    Key.PageUp -> -scroll.viewportSize * ARTICLE_PAGE_FRACTION
                    Key.PageDown -> scroll.viewportSize * ARTICLE_PAGE_FRACTION
                    Key.MoveHome -> -scroll.maxValue.toFloat()
                    Key.MoveEnd -> scroll.maxValue.toFloat()
                    else -> return@onKeyEvent false
                }
                scope.launch { scroll.scrollBy(delta) }
                true
            },
    ) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(ARTICLE_PANE_PADDING)) {
            if (article == null) return@Column
            BasicText(
                text = article.title,
                style = style.textStyle(
                    fontSize = (style.articleFontSize.value + ARTICLE_TITLE_EXTRA_SIZE.value).sp,
                    bold = true,
                    color = if (article.link != null) style.colors.link else style.colors.text,
                ),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = article.link != null,
                    onClick = actions::openSelectedArticle,
                ),
            )
            Spacer(Modifier.height(BAR_PADDING))
            BasicText(
                text = listOf(formatListDateTime(article.sortAt), state.channelNames[article.channelId].orEmpty())
                    .filter(String::isNotEmpty)
                    .joinToString("  "),
                style = style.textStyle(color = style.colors.textDim),
            )
            Spacer(Modifier.height(ARTICLE_PANE_PADDING))
            state.articleContent?.takeIf { it.articleId == article.id }?.let { content ->
                // 本文の文字を、ドラッグで選んでコピーできるようにする。
                SelectionContainer { HtmlContent(content.blocks, actions::openLink, Modifier.fillMaxWidth()) }
            }
        }
        ContentScrollbar(scroll, Modifier.width(SCROLLBAR_WIDTH).fillMaxHeight())
    }
}

@Composable
private fun StatusBar(state: MainUiState) {
    val style = LocalLegirssStyle.current
    val borderColor = style.colors.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(style.statusBarHeight)
            .drawBehind { drawLine(borderColor, Offset.Zero, Offset(size.width, 0f)) }
            .padding(horizontal = BAR_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "${state.selectedNodeName}  ${state.selectedNodeCount.total} 件  未読 ${state.selectedNodeCount.unread} 件",
            style = style.textStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f))
        BasicText(
            text = state.updateProgress?.let(::progressText) ?: state.statusMessage.orEmpty(),
            style = style.textStyle(),
            maxLines = 1,
        )
        Spacer(Modifier.width(TOOLBAR_GROUP_GAP))
        BasicText(text = "表示 ${state.articles.size} 件", style = style.textStyle(color = style.colors.textDim))
    }
}

private fun progressText(progress: UpdateProgress): String = buildString {
    append("更新中 ${progress.finished}/${progress.total}  新着 ${progress.newArticles} 件")
    if (progress.failed > 0) append("  失敗 ${progress.failed} 件")
}
