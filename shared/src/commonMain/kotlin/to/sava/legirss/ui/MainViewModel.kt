package to.sava.legirss.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.TreeNode
import to.sava.legirss.update.FeedUpdater
import to.sava.legirss.update.UpdateProgress

/** メイン画面の状態を持ち、操作を受けて更新する。 */
class MainViewModel(
    private val store: LegirssStore,
    private val updater: FeedUpdater,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) : MainActions {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var nodes: List<TreeNode> = emptyList()
    private var channelCounts: Map<Long, ArticleCount> = emptyMap()
    private var tree = TreeModel(nodes, channelCounts)
    private var expanded: Set<NodeSelection> = setOf(NodeSelection.All)

    /** 選んでいるノードの記事。絞り込みと並べ替えを掛ける前のもの。 */
    private var loadedArticles: List<ArticleRow> = emptyList()

    private var updateJob: Job? = null

    init {
        scope.launch {
            updater.progress.collect { progress -> mutableState.update { it.copy(updateProgress = progress) } }
        }
    }

    fun load() {
        scope.launch { reload() }
    }

    override fun selectNode(selection: NodeSelection) {
        scope.launch { showNode(selection) }
    }

    override fun toggleNode(selection: NodeSelection) {
        expanded = if (selection in expanded) expanded - selection else expanded + selection
        mutableState.update { it.copy(treeRows = tree.rows(expanded)) }
    }

    override fun setFilter(filter: StateFilter) {
        mutableState.update { it.copy(filter = filter).withArrangedArticles() }
    }

    override fun cycleFilter() {
        setFilter(mutableState.value.filter.next())
    }

    override fun sortBy(column: ArticleColumn) {
        mutableState.update { it.copy(sort = it.sort.toggledBy(column)).withArrangedArticles() }
    }

    /** 記事を選ぶ。未読なら既読にする。 */
    override fun selectArticle(index: Int) {
        val article = mutableState.value.articles.getOrNull(index) ?: return
        mutableState.update { it.copy(selectedArticleIndex = index) }
        if (article.state == ArticleState.UNREAD) changeState(index, ArticleState.READ)
    }

    /** 一番古い未読の記事を選ぶ。未読が無ければ何もしない。 */
    fun selectOldestUnread() {
        mutableState.value.oldestUnreadIndex?.let(::selectArticle)
    }

    /**
     * 読み進める。記事を選んでいなければ一番古い未読を選び、選んでいれば 1 つ上の行へ進む。
     */
    override fun readOn() {
        when (val index = mutableState.value.selectedArticleIndex) {
            null -> selectOldestUnread()
            else -> if (index > 0) selectArticle(index - 1)
        }
    }

    /** 選んでいる記事の閲覧状況を 未読 → 既読 → 保護 の順（[forward] が false なら逆順）で切り替える。 */
    override fun cycleSelectedState(forward: Boolean) {
        val current = mutableState.value
        val index = current.selectedArticleIndex ?: return
        val article = current.articles.getOrNull(index) ?: return
        changeState(index, article.state.cycled(forward))
    }

    /** 選んでいる記事を削除する。選択は同じ位置に残す。 */
    override fun deleteSelectedArticle() {
        val current = mutableState.value
        val index = current.selectedArticleIndex ?: return
        val article = current.articles.getOrNull(index) ?: return
        loadedArticles = loadedArticles.filterNot { it.id == article.id }
        adjustCount(article.channelId, totalDelta = -1, unreadDelta = if (article.state == ArticleState.UNREAD) -1 else 0)
        mutableState.update { state ->
            val remaining = state.articles.filterNot { it.id == article.id }
            state.copy(articles = remaining, selectedArticleIndex = index.coerceAtMost(remaining.lastIndex).takeIf { it >= 0 })
                .withTree()
        }
        scope.launch { store.deleteArticle(article.id, now()) }
    }

    /** 全てのチャンネルを更新する。更新中なら中止する。 */
    override fun updateAll() {
        startOrCancelUpdate { null }
    }

    /** 選んでいるノード以下のチャンネルを更新する。更新中なら中止する。 */
    override fun updateSelected() {
        startOrCancelUpdate { tree.channelIdsUnder(mutableState.value.selectedNode) }
    }

    private fun startOrCancelUpdate(channelIds: () -> List<Long>?) {
        updateJob?.takeIf { it.isActive }?.let { running ->
            running.cancel()
            return
        }
        updateJob = scope.launch {
            mutableState.update { it.copy(statusMessage = null) }
            try {
                val result = updater.update(channelIds())
                mutableState.update { it.copy(statusMessage = summaryOf(result)) }
            } catch (cancellation: CancellationException) {
                mutableState.update { it.copy(statusMessage = UPDATE_CANCELLED_MESSAGE) }
                throw cancellation
            } finally {
                // 中止した場合も、そこまでに取り込んだ分を画面に反映する。
                withContext(NonCancellable) { reload() }
            }
        }
    }

    private fun summaryOf(result: UpdateProgress): String = buildString {
        append("更新しました  新着 ${result.newArticles} 件")
        if (result.failed > 0) append("  失敗 ${result.failed} 件")
    }

    private fun changeState(index: Int, newState: ArticleState) {
        val article = mutableState.value.articles.getOrNull(index)?.takeIf { it.state != newState } ?: return
        val changed = article.copy(state = newState)
        loadedArticles = loadedArticles.map { if (it.id == article.id) changed else it }
        adjustCount(
            channelId = article.channelId,
            totalDelta = 0,
            unreadDelta = unreadWeight(newState) - unreadWeight(article.state),
        )
        // 読んでいる途中で行が動かないよう、並べ直さずにその行だけを差し替える。
        mutableState.update { state ->
            state.copy(articles = state.articles.map { if (it.id == article.id) changed else it }).withTree()
        }
        scope.launch { store.setArticleState(article.id, newState) }
    }

    private fun unreadWeight(state: ArticleState): Int = if (state == ArticleState.UNREAD) 1 else 0

    private fun adjustCount(channelId: Long, totalDelta: Int, unreadDelta: Int) {
        val count = channelCounts[channelId] ?: ArticleCount.ZERO
        channelCounts = channelCounts + (channelId to ArticleCount(count.total + totalDelta, count.unread + unreadDelta))
        tree = TreeModel(nodes, channelCounts)
    }

    private suspend fun reload() {
        nodes = store.loadTree()
        channelCounts = store.loadArticleCounts()
        tree = TreeModel(nodes, channelCounts)
        mutableState.update { it.copy(channelNames = tree.channelNames) }
        showNode(mutableState.value.selectedNode)
    }

    private suspend fun showNode(selection: NodeSelection) {
        loadedArticles = store.loadArticleRows(tree.channelIdsUnder(selection))
        mutableState.update { it.copy(selectedNode = selection).withTree().withArrangedArticles() }
    }

    private fun MainUiState.withTree(): MainUiState = copy(
        treeRows = tree.rows(expanded),
        selectedNodeName = tree.nameOf(selectedNode),
        selectedNodeCount = tree.countOf(selectedNode),
    )

    /** 一覧を作り直す。行の並びが変わるので選択は外す。 */
    private fun MainUiState.withArrangedArticles(): MainUiState = copy(
        articles = arrangeArticles(loadedArticles, filter, sort, channelNames),
        selectedArticleIndex = null,
    )

    private companion object {
        const val UPDATE_CANCELLED_MESSAGE = "更新を中止しました"
    }
}
