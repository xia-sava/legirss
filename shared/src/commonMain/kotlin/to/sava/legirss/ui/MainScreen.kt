package to.sava.legirss.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.ui.component.DataTable
import to.sava.legirss.ui.component.HorizontalSplit
import to.sava.legirss.ui.component.HtmlContent
import to.sava.legirss.ui.component.SegmentedButtons
import to.sava.legirss.ui.component.TableColumn
import to.sava.legirss.ui.component.TableText
import to.sava.legirss.ui.component.ToolbarButton
import to.sava.legirss.ui.component.TreeView
import to.sava.legirss.ui.component.VerticalSplit
import to.sava.legirss.ui.platform.ContentScrollbar
import to.sava.legirss.ui.platform.formatListDateTime
import to.sava.legirss.ui.theme.LegirssColors
import to.sava.legirss.ui.theme.LocalLegirssStyle
import to.sava.legirss.update.UpdateProgress

private val DEFAULT_TREE_WIDTH = 240.dp
private val DEFAULT_LIST_HEIGHT = 260.dp
private val DEFAULT_COLUMN_WIDTHS = mapOf(
    ArticleColumn.STATE to 22.dp,
    ArticleColumn.DATE to 84.dp,
    ArticleColumn.TITLE to 460.dp,
    ArticleColumn.CHANNEL to 160.dp,
    ArticleColumn.CATEGORY to 110.dp,
    ArticleColumn.AUTHOR to 110.dp,
)
private val BAR_PADDING = 4.dp
private val TOOLBAR_GROUP_GAP = 8.dp
private val STATE_ICON_SIZE = 9.dp
private val ARTICLE_PANE_PADDING = 8.dp
private val SCROLLBAR_WIDTH = 10.dp
private val ARTICLE_TITLE_EXTRA_SIZE = 3.sp

private const val UPDATE_ALL_LABEL = "更新"
private const val UPDATE_SELECTED_LABEL = "選択更新"
private const val CANCEL_UPDATE_LABEL = "中止"

/**
 * @param articleScroll 記事の表示領域のスクロール位置。キー操作で本文を送るために、呼び出し側と共有する。
 */
@Composable
fun MainScreen(viewModel: MainViewModel, articleScroll: ScrollState, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    MainScreen(state, viewModel, articleScroll, modifier)
}

@Composable
fun MainScreen(state: MainUiState, actions: MainActions, articleScroll: ScrollState, modifier: Modifier = Modifier) {
    val style = LocalLegirssStyle.current
    var treeWidth by remember { mutableStateOf(DEFAULT_TREE_WIDTH) }
    var listHeight by remember { mutableStateOf(DEFAULT_LIST_HEIGHT) }
    val columnWidths = remember { mutableStateMapOf<ArticleColumn, Dp>().apply { putAll(DEFAULT_COLUMN_WIDTHS) } }
    Column(modifier.fillMaxSize().background(style.colors.panel)) {
        Toolbar(state, actions)
        HorizontalSplit(
            firstWidth = treeWidth,
            onFirstWidthChange = { treeWidth = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            first = {
                TreeView(
                    rows = state.treeRows,
                    selectedKey = state.selectedNode,
                    onSelect = actions::selectNode,
                    onToggle = actions::toggleNode,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            second = {
                VerticalSplit(
                    firstHeight = listHeight,
                    onFirstHeightChange = { listHeight = it },
                    modifier = Modifier.fillMaxSize(),
                    first = {
                        ArticleTable(
                            state = state,
                            actions = actions,
                            columnWidths = columnWidths,
                            onColumnResize = { column, width -> columnWidths[column] = width },
                        )
                    },
                    second = { ArticlePane(state, actions, articleScroll) },
                )
            },
        )
        StatusBar(state)
    }
}

@Composable
private fun Toolbar(state: MainUiState, actions: MainActions) {
    val style = LocalLegirssStyle.current
    val updating = state.updateProgress != null
    Row(
        modifier = Modifier.fillMaxWidth().height(style.toolbarHeight).padding(horizontal = BAR_PADDING, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(if (updating) CANCEL_UPDATE_LABEL else UPDATE_ALL_LABEL, onClick = actions::updateAll)
        if (!updating) ToolbarButton(UPDATE_SELECTED_LABEL, onClick = actions::updateSelected)
        Spacer(Modifier.width(TOOLBAR_GROUP_GAP))
        SegmentedButtons(
            options = StateFilter.entries,
            selected = state.filter,
            label = StateFilter::label,
            onSelect = actions::setFilter,
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

@Composable
private fun ArticleTable(
    state: MainUiState,
    actions: MainActions,
    columnWidths: Map<ArticleColumn, Dp>,
    onColumnResize: (ArticleColumn, Dp) -> Unit,
) {
    val colors = LocalLegirssStyle.current.colors
    val focusRequester = remember { FocusRequester() }

    // 起動した直後と、しまっていたウィンドウを出した直後に、キーですぐ読み始められるようフォーカスを一覧に置く。
    // 一覧が画面に組み込まれた後に実行されるよう、一覧と同じ場所で行う。
    LaunchedEffect(state.listFocusRequest) { focusRequester.requestFocus() }

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
        onSelectIndex = actions::selectArticle,
        sortedColumn = state.sort.column,
        sortDescending = state.sort.descending,
        onColumnClick = actions::sortBy,
        onColumnResize = onColumnResize,
        centerSelection = true,
        modifier = Modifier.fillMaxSize(),
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

/** 未読は塗った丸、既読は輪郭だけの丸、保護は塗った四角で表す。 */
private fun DrawScope.drawStateIcon(state: ArticleState, colors: LegirssColors) {
    when (state) {
        ArticleState.UNREAD -> drawCircle(colors.iconUnread)
        ArticleState.READ -> drawCircle(colors.iconPaused, radius = size.minDimension / 2f - 1f, style = Stroke(1f))
        ArticleState.PROTECTED -> drawRect(colors.iconProtected)
    }
}

/** 選んでいる記事の、タイトル・日付とチャンネル名・本文を上から順に出す。 */
@Composable
private fun ArticlePane(state: MainUiState, actions: MainActions, scroll: ScrollState) {
    val style = LocalLegirssStyle.current
    val article = state.selectedArticle

    // 別の記事を選んだら、先頭から読めるようにスクロール位置を戻す。
    LaunchedEffect(article?.id) { scroll.scrollTo(0) }

    Row(Modifier.fillMaxSize().background(style.colors.background)) {
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
                HtmlContent(content.blocks, actions::openLink, Modifier.fillMaxWidth())
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
