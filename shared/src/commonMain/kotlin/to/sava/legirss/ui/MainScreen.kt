package to.sava.legirss.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import to.sava.legirss.ui.component.SegmentedButtons
import to.sava.legirss.ui.component.TableColumn
import to.sava.legirss.ui.component.TableText
import to.sava.legirss.ui.component.TreeView
import to.sava.legirss.ui.component.VerticalSplit
import to.sava.legirss.ui.platform.formatListDateTime
import to.sava.legirss.ui.theme.LegirssColors
import to.sava.legirss.ui.theme.LocalLegirssStyle

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
private val STATE_ICON_SIZE = 9.dp
private val ARTICLE_PANE_PADDING = 8.dp
private val ARTICLE_TITLE_EXTRA_SIZE = 3.sp

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    MainScreen(
        state = state,
        onSelectNode = viewModel::selectNode,
        onToggleNode = viewModel::toggleNode,
        onSelectArticle = viewModel::selectArticle,
        onFilterChange = viewModel::setFilter,
        onSortBy = viewModel::sortBy,
        modifier = modifier,
    )
}

@Composable
fun MainScreen(
    state: MainUiState,
    onSelectNode: (NodeSelection) -> Unit,
    onToggleNode: (NodeSelection) -> Unit,
    onSelectArticle: (Int) -> Unit,
    onFilterChange: (StateFilter) -> Unit,
    onSortBy: (ArticleColumn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalLegirssStyle.current
    var treeWidth by remember { mutableStateOf(DEFAULT_TREE_WIDTH) }
    var listHeight by remember { mutableStateOf(DEFAULT_LIST_HEIGHT) }
    val columnWidths = remember { mutableStateMapOf<ArticleColumn, Dp>().apply { putAll(DEFAULT_COLUMN_WIDTHS) } }

    Column(modifier.fillMaxSize().background(style.colors.panel)) {
        Toolbar(state.filter, onFilterChange)
        HorizontalSplit(
            firstWidth = treeWidth,
            onFirstWidthChange = { treeWidth = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            first = {
                TreeView(
                    rows = state.treeRows,
                    selectedKey = state.selectedNode,
                    onSelect = onSelectNode,
                    onToggle = onToggleNode,
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
                            columnWidths = columnWidths,
                            onSelectArticle = onSelectArticle,
                            onSortBy = onSortBy,
                            onColumnResize = { column, width -> columnWidths[column] = width },
                        )
                    },
                    second = { ArticlePane(state.selectedArticle, state.channelNames) },
                )
            },
        )
        StatusBar(state)
    }
}

@Composable
private fun Toolbar(filter: StateFilter, onFilterChange: (StateFilter) -> Unit) {
    val style = LocalLegirssStyle.current
    Row(
        modifier = Modifier.fillMaxWidth().height(style.toolbarHeight).padding(horizontal = BAR_PADDING, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentedButtons(
            options = StateFilter.entries,
            selected = filter,
            label = StateFilter::label,
            onSelect = onFilterChange,
        )
    }
}

@Composable
private fun ArticleTable(
    state: MainUiState,
    columnWidths: Map<ArticleColumn, Dp>,
    onSelectArticle: (Int) -> Unit,
    onSortBy: (ArticleColumn) -> Unit,
    onColumnResize: (ArticleColumn, Dp) -> Unit,
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
        onSelectIndex = onSelectArticle,
        sortedColumn = state.sort.column,
        sortDescending = state.sort.descending,
        onColumnClick = onSortBy,
        onColumnResize = onColumnResize,
        centerSelection = true,
        modifier = Modifier.fillMaxSize(),
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

@Composable
private fun ArticlePane(article: ArticleRow?, channelNames: Map<Long, String>) {
    val style = LocalLegirssStyle.current
    Column(Modifier.fillMaxSize().background(style.colors.background).padding(ARTICLE_PANE_PADDING)) {
        if (article == null) return@Column
        BasicText(
            text = article.title,
            style = style.textStyle((style.listFontSize.value + ARTICLE_TITLE_EXTRA_SIZE.value).sp, bold = true),
        )
        Spacer(Modifier.height(BAR_PADDING))
        BasicText(
            text = listOf(formatListDateTime(article.sortAt), channelNames[article.channelId].orEmpty())
                .filter(String::isNotEmpty)
                .joinToString("  "),
            style = style.textStyle(color = style.colors.textDim),
        )
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
        Spacer(Modifier.width(BAR_PADDING))
        Box(Modifier.weight(1f))
        BasicText(text = "表示 ${state.articles.size} 件", style = style.textStyle(color = style.colors.textDim))
    }
}
