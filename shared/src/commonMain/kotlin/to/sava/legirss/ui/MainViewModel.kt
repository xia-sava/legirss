package to.sava.legirss.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.html.HtmlBlocks
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
    parentScope: CoroutineScope,
    /** 記事の本文の解釈に使う。 */
    private val parseDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    /** URL を外部のブラウザで開く。 */
    private val openUrl: (String) -> Unit,
    /** 想定していない失敗を、説明と原因を添えて記録する。 */
    private val reportError: (String, Throwable) -> Unit,
) : MainActions {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    /**
     * 操作ごとの処理を動かす場所。
     *
     * 1 つの処理が失敗しても、ほかの処理と以後の操作を止めない。失敗は記録してステータスに出す。
     */
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, exception ->
                reportError(UNEXPECTED_ERROR_MESSAGE, exception)
                mutableState.update { it.copy(statusMessage = UNEXPECTED_ERROR_MESSAGE) }
            },
    )

    private var nodes: List<TreeNode> = emptyList()
    private var channelCounts: Map<Long, ArticleCount> = emptyMap()
    private var tree = TreeModel(nodes, channelCounts)
    private var expanded: Set<NodeSelection> = setOf(NodeSelection.All)

    /** 選んでいるノードの記事。絞り込みと並べ替えを掛ける前のもの。 */
    private var loadedArticles: List<ArticleRow> = emptyList()

    private var updateJob: Job? = null
    private var contentJob: Job? = null
    private var windowVisible = true

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
        showSelectedContent()
    }

    /** 選んでいる記事のリンクを外部のブラウザで開く。 */
    override fun openSelectedArticle() {
        mutableState.value.selectedArticle?.link?.let(openUrl)
    }

    override fun openLink(url: String) {
        openUrl(url)
    }

    /** 選んでいる記事の本文を読み出して表示する。選んでいなければ表示を消す。 */
    private fun showSelectedContent() {
        contentJob?.cancel()
        val article = mutableState.value.selectedArticle
        if (article == null) {
            mutableState.update { it.copy(articleContent = null) }
            return
        }
        contentJob = scope.launch {
            val body = store.loadArticleBody(article.id)
            val blocks = withContext(parseDispatcher) { body?.text?.let { HtmlBlocks.parse(it, body.link) }.orEmpty() }
            // 読み出している間に選択が変わっていたら、古い結果は捨てる。
            mutableState.update { state ->
                if (state.selectedArticle?.id == article.id) state.copy(articleContent = ArticleContent(article.id, blocks)) else state
            }
        }
    }

    /**
     * 読み進める。一覧で一番下にある未読の記事を選ぶ。未読が無ければ何もしない。
     *
     * 選んだ記事は既読になるので、繰り返すと未読を下から順にたどることになる。
     */
    override fun readOn() {
        mutableState.value.lastUnreadIndex?.let(::selectArticle)
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
        showSelectedContent()
        scope.launch { store.deleteArticle(article.id, now()) }
    }

    /** 全てのチャンネルを更新する。更新中なら中止する。 */
    override fun updateAll() {
        startOrCancelUpdate(allChannels = true)
    }

    /** 選んでいるノード以下のチャンネルを更新する。更新中なら中止する。 */
    override fun updateSelected() {
        startOrCancelUpdate(allChannels = false)
    }

    /** 一覧を読み直して並べ直す。 */
    override fun reloadList() {
        scope.launch { reload() }
    }

    /** 画面の配置などの設定を保存する。[settings] に無い名前の設定は変えない。 */
    fun saveSettings(settings: Map<String, String>) {
        scope.launch { store.saveSettings(settings) }
    }

    /**
     * 定期更新として全てのチャンネルを更新する。すでに更新が動いていれば、新しくは始めずにそれを返す。
     *
     * ウィンドウを表示している間は、読んでいる途中で行が動かないよう、終わっても一覧を並べ直さない。
     */
    fun updateAutomatically(): Job =
        updateJob?.takeIf { it.isActive } ?: launchUpdate(allChannels = true, automatic = true)

    /** 次の定期更新までの待ち時間。前回の全体更新から [intervalMillis] が過ぎていれば 0。 */
    suspend fun millisUntilNextAutomaticUpdate(intervalMillis: Long): Long {
        val lastUpdatedAt = store.loadSetting(LAST_UPDATE_KEY)?.toLongOrNull() ?: return 0
        return (lastUpdatedAt + intervalMillis - now()).coerceIn(0, intervalMillis)
    }

    /** [intervalMillis] ごとに定期更新を続ける。呼び出したコルーチンが取り消されるまで戻らない。 */
    suspend fun runAutomaticUpdates(intervalMillis: Long): Nothing {
        while (true) {
            delay(millisUntilNextAutomaticUpdate(intervalMillis))
            updateAutomatically().join()
        }
    }

    /**
     * ウィンドウを表示したか、しまったかを伝える。
     *
     * 表示したときは一覧を並べ直して選択を外し、一覧にフォーカスを置く。
     */
    fun setWindowVisible(visible: Boolean) {
        if (windowVisible == visible) return
        windowVisible = visible
        if (!visible) return
        mutableState.update { it.copy(listFocusRequest = it.listFocusRequest + 1) }
        scope.launch { reload() }
    }

    private fun startOrCancelUpdate(allChannels: Boolean) {
        updateJob?.takeIf { it.isActive }?.let { running ->
            running.cancel()
            return
        }
        launchUpdate(allChannels, automatic = false)
    }

    private fun launchUpdate(allChannels: Boolean, automatic: Boolean): Job = scope.launch {
        mutableState.update { it.copy(statusMessage = null) }
        try {
            val result = updater.update(if (allChannels) null else tree.channelIdsUnder(mutableState.value.selectedNode))
            mutableState.update { it.copy(statusMessage = summaryOf(result)) }
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(statusMessage = UPDATE_CANCELLED_MESSAGE) }
            throw cancellation
        } finally {
            withContext(NonCancellable) {
                // 中止した場合も次の定期更新を待たせるため、全体更新を試みた時刻として記録する。
                if (allChannels) store.saveSetting(LAST_UPDATE_KEY, now().toString())
                // 中止した場合も、そこまでに取り込んだ分を画面に反映する。
                if (automatic && windowVisible) reloadTree() else reload()
            }
        }
    }.also { updateJob = it }

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
        reloadTree()
        showNode(mutableState.value.selectedNode)
    }

    /** ツリーと記事数だけを読み直す。一覧の行には触れない。 */
    private suspend fun reloadTree() {
        nodes = store.loadTree()
        channelCounts = store.loadArticleCounts()
        tree = TreeModel(nodes, channelCounts)
        mutableState.update { it.copy(channelNames = tree.channelNames).withTree() }
    }

    private suspend fun showNode(selection: NodeSelection) {
        loadedArticles = store.loadArticleRows(tree.channelIdsUnder(selection))
        mutableState.update { it.copy(selectedNode = selection).withTree().withArrangedArticles() }
    }

    private fun MainUiState.withTree(): MainUiState = copy(
        treeRows = tree.rows(expanded),
        selectedNodeName = tree.nameOf(selectedNode),
        selectedNodeCount = tree.countOf(selectedNode),
        totalCount = tree.totalCount,
    )

    /** 一覧を作り直す。行の並びが変わるので選択は外す。 */
    private fun MainUiState.withArrangedArticles(): MainUiState {
        contentJob?.cancel()
        return copy(
            articles = arrangeArticles(loadedArticles, filter, sort, channelNames),
            selectedArticleIndex = null,
            articleContent = null,
        )
    }

    private companion object {
        const val UPDATE_CANCELLED_MESSAGE = "更新を中止しました"
        const val UNEXPECTED_ERROR_MESSAGE = "エラーが発生しました"

        /** 全体の更新を最後に試みた時刻を保存する設定の名前。 */
        const val LAST_UPDATE_KEY = "last_update_at"
    }
}
