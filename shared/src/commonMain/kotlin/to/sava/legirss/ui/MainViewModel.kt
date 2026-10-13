package to.sava.legirss.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.model.ArticleRow

/** メイン画面の状態を持ち、操作を受けて更新する。 */
class MainViewModel(
    private val store: LegirssStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var tree = TreeModel(emptyList(), emptyMap())
    private var expanded: Set<NodeSelection> = setOf(NodeSelection.All)

    /** 選んでいるノードの記事。絞り込みと並べ替えを掛ける前のもの。 */
    private var loadedArticles: List<ArticleRow> = emptyList()

    fun load() {
        scope.launch {
            tree = TreeModel(store.loadTree(), store.loadArticleCounts())
            mutableState.update { it.copy(treeRows = tree.rows(expanded), channelNames = tree.channelNames) }
            showNode(mutableState.value.selectedNode)
        }
    }

    fun selectNode(selection: NodeSelection) {
        scope.launch { showNode(selection) }
    }

    fun toggleNode(selection: NodeSelection) {
        expanded = if (selection in expanded) expanded - selection else expanded + selection
        mutableState.update { it.copy(treeRows = tree.rows(expanded)) }
    }

    fun setFilter(filter: StateFilter) {
        mutableState.update { it.copy(filter = filter).withArrangedArticles() }
    }

    fun sortBy(column: ArticleColumn) {
        mutableState.update { it.copy(sort = it.sort.toggledBy(column)).withArrangedArticles() }
    }

    fun selectArticle(index: Int) {
        mutableState.update { it.copy(selectedArticleIndex = index) }
    }

    private suspend fun showNode(selection: NodeSelection) {
        loadedArticles = store.loadArticleRows(tree.channelIdsUnder(selection))
        mutableState.update {
            it.copy(
                selectedNode = selection,
                selectedNodeName = tree.nameOf(selection),
                selectedNodeCount = tree.countOf(selection),
            ).withArrangedArticles()
        }
    }

    /** 一覧を作り直す。行の並びが変わるので選択は外す。 */
    private fun MainUiState.withArrangedArticles(): MainUiState = copy(
        articles = arrangeArticles(loadedArticles, filter, sort, channelNames),
        selectedArticleIndex = null,
    )
}
