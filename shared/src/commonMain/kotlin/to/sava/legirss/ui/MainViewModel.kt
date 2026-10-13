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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.feed.FeedLocator
import to.sava.legirss.feed.FeedLookup
import to.sava.legirss.feed.FeedText
import to.sava.legirss.opml.Opml
import to.sava.legirss.html.HtmlBlocks
import to.sava.legirss.model.AppSettings
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode
import to.sava.legirss.ui.component.TreeDropPlace
import to.sava.legirss.update.FeedUpdater
import to.sava.legirss.update.UpdateProgress

/** メイン画面の状態を持ち、操作を受けて更新する。 */
class MainViewModel(
    private val store: LegirssStore,
    private val updater: FeedUpdater,
    /** チャンネルを追加するときに、入力された URL からフィードを探す。 */
    private val locator: FeedLocator,
    parentScope: CoroutineScope,
    /** 記事の本文の解釈に使う。 */
    private val parseDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    /** URL を外部のブラウザで開く。 */
    private val openUrl: (String) -> Unit,
    /** 文字をクリップボードへ写す。 */
    private val copyText: (String) -> Unit,
    private val opmlFiles: OpmlFiles,
    /** 想定していない失敗を、説明と原因を添えて記録する。 */
    private val reportError: (String, Throwable) -> Unit,
    /** 起動した時点で保存されていた設定。 */
    initialSettings: AppSettings,
) : MainActions {
    private val mutableState = MutableStateFlow(MainUiState(settings = initialSettings))
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

    /** 手前に出しているダイアログが進めている処理。 */
    private var dialogJob: Job? = null
    private var windowVisible = true

    init {
        scope.launch {
            updater.progress.collect { progress -> mutableState.update { it.copy(updateProgress = progress) } }
        }
        // 更新している最中のチャンネルを、ツリーのアイコンで示す。
        scope.launch {
            updater.updatingChannelIds.collect { updating -> mutableState.update { it.copy(treeRows = tree.rows(expanded, updating)) } }
        }
    }

    fun load() {
        scope.launch { reload() }
    }

    /** ノードを選ぶ。選択はすぐに切り替え、記事の一覧は読み出しが済んでから差し替える。 */
    override fun selectNode(selection: NodeSelection) {
        mutableState.update { it.copy(selectedNode = selection).withTree() }
        scope.launch { showNode(selection) }
    }

    override fun toggleNode(selection: NodeSelection) {
        expanded = if (selection in expanded) expanded - selection else expanded + selection
        mutableState.update { it.copy(treeRows = tree.rows(expanded, updater.updatingChannelIds.value)) }
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

    /** 記事を選んで表示する。未読なら既読にする。付けていた印は外す。 */
    override fun selectArticle(index: Int) {
        val article = mutableState.value.articles.getOrNull(index) ?: return
        mutableState.update { it.copy(selectedArticleIndex = index).withoutMarks() }
        if (article.state == ArticleState.UNREAD) changeStates(listOf(article), ArticleState.READ)
        showSelectedContent()
    }

    /**
     * 表示している記事から [index] までの範囲に印を付ける。表示している記事は変えず、既読にもしない。
     *
     * 記事を表示していなければ、[index] の記事だけに印を付ける。
     */
    override fun markArticlesTo(index: Int) {
        mutableState.update { state ->
            if (index !in state.articles.indices) return@update state
            val start = state.selectedArticleIndex ?: index
            val range = minOf(start, index)..maxOf(start, index)
            state.copy(markedArticleIds = state.articles.slice(range).map { it.id }.toSet(), markRangeEndIndex = index)
        }
    }

    /** [index] の記事の印を付け外しする。最初の 1 つを付けるときは、表示している記事にも印を付ける。 */
    override fun toggleArticleMark(index: Int) {
        mutableState.update { state ->
            val id = state.articles.getOrNull(index)?.id ?: return@update state
            val marked = state.markedArticleIds.ifEmpty { setOfNotNull(state.selectedArticle?.id) }
            state.copy(markedArticleIds = if (id in marked) marked - id else marked + id, markRangeEndIndex = null)
        }
    }

    override fun markAllArticles() {
        mutableState.update { state -> state.copy(markedArticleIds = state.articles.map { it.id }.toSet(), markRangeEndIndex = null) }
    }

    /** 操作の対象の記事のリンクを外部のブラウザで開く。 */
    override fun openSelectedArticle() {
        mutableState.value.targetArticles.mapNotNull { it.link }.forEach(openUrl)
    }

    /** 操作の対象の記事の [content] を、1 件を 1 行にしてクリップボードへ写す。対象が無ければ何もしない。 */
    override fun copySelected(content: CopyContent) {
        mutableState.value.targetArticles
            .mapNotNull { article ->
                when (content) {
                    CopyContent.URL -> article.link
                    CopyContent.TITLE -> article.title
                    CopyContent.TITLE_AND_URL -> listOfNotNull(article.title, article.link).joinToString(" ")
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.let { copyText(it.joinToString("\n")) }
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

    /**
     * 操作の対象の記事の未読と既読を切り替える。
     *
     * 対象が複数あるときは、表示している記事（対象に入っていなければ先頭の対象）を切り替えたあとの状況に、全てをそろえる。
     */
    override fun toggleSelectedState() {
        val current = mutableState.value
        val targets = current.targetArticles
        val reference = current.selectedArticle?.takeIf { it in targets } ?: targets.firstOrNull() ?: return
        changeStates(targets, reference.state.toggled())
    }

    /** 操作の対象の記事の閲覧状況を [state] にする。 */
    override fun setSelectedState(state: ArticleState) {
        changeStates(mutableState.value.targetArticles, state)
    }

    /** 操作の対象の記事を削除する。表示していた記事を消したら、同じ位置に来た記事を選ぶ。 */
    override fun deleteSelectedArticles() {
        val current = mutableState.value
        val targets = current.targetArticles.ifEmpty { return }
        val removedIds = targets.map { it.id }.toSet()
        loadedArticles = loadedArticles.filterNot { it.id in removedIds }
        targets.forEach { article ->
            adjustCount(article.channelId, totalDelta = -1, unreadDelta = -unreadWeight(article.state))
        }
        mutableState.update { state ->
            val remaining = state.articles.filterNot { it.id in removedIds }
            // 表示していた記事より上で消えた分だけ、位置を上へ詰める。
            val index = state.selectedArticleIndex?.let { selected ->
                (selected - state.articles.take(selected).count { it.id in removedIds }).coerceAtMost(remaining.lastIndex).takeIf { it >= 0 }
            }
            state.copy(articles = remaining, selectedArticleIndex = index).withoutMarks().withTree()
        }
        showSelectedContent()
        scope.launch { store.deleteArticles(removedIds, now()) }
    }

    /** 全てのチャンネルを更新する。更新中なら中止する。 */
    override fun updateAll() {
        startOrCancelUpdate(channelIds = null)
    }

    /** 選んでいるノード以下のチャンネルを更新する。更新中なら中止する。 */
    override fun updateSelected() {
        updateNode(mutableState.value.selectedNode)
    }

    /** [selection] 以下のチャンネルを更新する。更新中なら中止する。 */
    override fun updateNode(selection: NodeSelection) {
        startOrCancelUpdate(tree.channelIdsUnder(selection))
    }

    /** [selection] 以下のチャンネルを購読中止にする、または購読を再開する。「全ての記事」に対しては何もしない。 */
    override fun setNodePaused(selection: NodeSelection, paused: Boolean) {
        val channelIds = tree.channelIdsUnder(selection) ?: return
        scope.launch {
            store.setChannelsPaused(channelIds, paused)
            reloadTree()
        }
    }

    /** 一覧を読み直して並べ直す。 */
    override fun reloadList() {
        scope.launch { reload() }
    }

    override fun openAddChannel() {
        mutableState.update { it.copy(dialog = DialogState.AddChannel()) }
    }

    /** 手前に出しているダイアログを閉じる。ダイアログが進めていた処理は取り消し、フォーカスを一覧へ戻す。 */
    override fun closeDialog() {
        dialogJob?.cancel()
        mutableState.update { it.copy(dialog = null, listFocusRequest = it.listFocusRequest + 1) }
    }

    /**
     * [url] からフィードを探し、1 つに決まれば登録する。
     *
     * ページが複数のフィードを案内していれば、候補をダイアログに出して選ばせる。
     * 登録済みのフィードなら一度知らせ、同じフィードをもう一度指定されたら重ねて登録する。
     */
    override fun submitAddChannel(url: String) {
        val dialog = mutableState.value.dialog as? DialogState.AddChannel ?: return
        if (dialog.busy || url.isBlank()) return
        updateAddChannelDialog { it.copy(busy = true, message = null) }
        dialogJob = scope.launch {
            try {
                when (val lookup = locator.locate(url)) {
                    is FeedLookup.Failed -> updateAddChannelDialog { it.copy(message = lookup.reason, candidates = emptyList()) }
                    is FeedLookup.Candidates ->
                        updateAddChannelDialog { it.copy(message = CHOOSE_FEED_MESSAGE, candidates = lookup.candidates) }
                    is FeedLookup.Found -> when {
                        tree.hasFeed(lookup.feedUrl) && dialog.duplicateUrl != lookup.feedUrl ->
                            updateAddChannelDialog { it.copy(message = DUPLICATE_FEED_MESSAGE, duplicateUrl = lookup.feedUrl) }
                        else -> registerChannel(lookup)
                    }
                }
            } finally {
                updateAddChannelDialog { it.copy(busy = false) }
            }
        }
    }

    override fun openAddGroup() {
        mutableState.update { it.copy(dialog = DialogState.EditText(EditTarget.NewGroup, initialText = "")) }
    }

    override fun openRename(selection: NodeSelection) {
        val node = tree.nodeOf(selection) ?: return
        mutableState.update { it.copy(dialog = DialogState.EditText(EditTarget.NodeName(node.id), node.name)) }
    }

    override fun openChangeFeedUrl(selection: NodeSelection) {
        val node = tree.nodeOf(selection) ?: return
        val channel = node.channel ?: return
        mutableState.update { it.copy(dialog = DialogState.EditText(EditTarget.FeedUrl(node.id), channel.feedUrl)) }
    }

    /** 入力のダイアログで決めた文字を、ダイアログの目的に合わせて保存する。前後の空白を除いて空なら何もしない。 */
    override fun submitText(text: String) {
        val dialog = mutableState.value.dialog as? DialogState.EditText ?: return
        val entered = text.trim().ifEmpty { return }
        closeDialog()
        scope.launch {
            when (val target = dialog.target) {
                EditTarget.NewGroup -> addGroup(entered)
                is EditTarget.NodeName -> store.renameNode(target.nodeId, entered)
                is EditTarget.FeedUrl -> store.setFeedUrl(target.channelId, entered)
            }
            reloadTree()
        }
    }

    private suspend fun addGroup(name: String) {
        val parentId = tree.parentForNewNode(mutableState.value.selectedNode)
        store.addGroup(parentId, name)
        expanded = expanded + NodeSelection.All + listOfNotNull(parentId?.let(NodeSelection::Node))
    }

    /** [selection] のノードを削除してよいかを尋ねるダイアログを出す。「全ての記事」に対しては何もしない。 */
    override fun requestDelete(selection: NodeSelection) {
        val node = tree.nodeOf(selection) ?: return
        mutableState.update { it.copy(dialog = DialogState.ConfirmDelete(node.id, node.name, node.kind)) }
    }

    /** 確認のダイアログで尋ねていたノードを削除する。削除したノードの親を選び直す。 */
    override fun confirmDelete() {
        val dialog = mutableState.value.dialog as? DialogState.ConfirmDelete ?: return
        val parent = tree.nodeOf(NodeSelection.Node(dialog.nodeId))?.parentId?.let(NodeSelection::Node) ?: NodeSelection.All
        closeDialog()
        scope.launch {
            store.deleteNode(dialog.nodeId)
            reloadTree()
            showNode(parent)
        }
    }

    /** [selection] の直下のノードを名前の順に並べ直す。チャンネルを指定されたら何もしない。 */
    override fun sortChildrenByName(selection: NodeSelection) {
        val parentId = when (selection) {
            NodeSelection.All -> null
            is NodeSelection.Node -> tree.nodeOf(selection)?.takeIf { it.kind == NodeKind.GROUP }?.id ?: return
        }
        scope.launch {
            store.sortChildrenByName(parentId)
            reloadTree()
        }
    }

    /** [source] のノードを、[target] の行の [place] へ移す。移せない組み合わせなら何もしない。 */
    override fun moveNode(source: NodeSelection, target: NodeSelection, place: TreeDropPlace) {
        val nodeId = (source as? NodeSelection.Node)?.id ?: return
        val destination = tree.moveDestination(source, target, place) ?: return
        scope.launch {
            store.moveNode(nodeId, destination.parentId, destination.index)
            expanded = expanded + listOfNotNull(destination.parentId?.let(NodeSelection::Node))
            reloadTree()
        }
    }

    /**
     * 利用者が選んだ OPML のファイルから、チャンネルとグループを最上位の末尾へ取り込む。
     *
     * 登録済みのフィードは取り込まない。
     */
    override fun importOpml() {
        scope.launch {
            val bytes = try {
                opmlFiles.pick() ?: return@launch
            } catch (exception: IOException) {
                reportFileFailure(OPML_READ_FAILED_MESSAGE, exception)
                return@launch
            }
            val nodes = withContext(parseDispatcher) { Opml.parse(FeedText.decode(bytes, httpCharset = null)) }
            if (nodes == null) {
                mutableState.update { it.copy(statusMessage = NOT_OPML_MESSAGE) }
                return@launch
            }
            val added = Opml.withoutKnownFeeds(nodes, tree.feedUrls)
            store.importNodes(added)
            mutableState.update { it.copy(statusMessage = "OPML を読み込みました  チャンネル ${Opml.countChannels(added)} 件") }
            reloadTree()
        }
    }

    /** [selection] のグループの中（「全ての記事」なら全体）を、利用者が選んだ場所へ OPML として書き出す。 */
    override fun exportOpml(selection: NodeSelection) {
        val group = tree.nodeOf(selection)
        if (group != null && group.kind != NodeKind.GROUP) return
        val name = group?.name ?: APP_NAME
        val text = Opml.write(nodes, parentId = group?.id, title = name)
        scope.launch {
            try {
                if (opmlFiles.save("${name.replace(unsafeFileNameCharacters, "_")}$OPML_EXTENSION", text)) {
                    mutableState.update { it.copy(statusMessage = OPML_WRITTEN_MESSAGE) }
                }
            } catch (exception: IOException) {
                reportFileFailure(OPML_WRITE_FAILED_MESSAGE, exception)
            }
        }
    }

    private fun reportFileFailure(message: String, exception: IOException) {
        reportError(message, exception)
        mutableState.update { it.copy(statusMessage = message) }
    }

    private fun updateAddChannelDialog(change: (DialogState.AddChannel) -> DialogState.AddChannel) {
        mutableState.update { state -> (state.dialog as? DialogState.AddChannel)?.let { state.copy(dialog = change(it)) } ?: state }
    }

    /** フィードをチャンネルとして登録して選び、記事を取得する。 */
    private suspend fun registerChannel(feed: FeedLookup.Found) {
        val parentId = tree.parentForNewNode(mutableState.value.selectedNode)
        val channelId = store.addChannel(parentId, feed.title?.takeIf(String::isNotBlank) ?: feed.feedUrl, feed.feedUrl)
        expanded = expanded + NodeSelection.All + listOfNotNull(parentId?.let(NodeSelection::Node))
        mutableState.update { it.copy(dialog = null, listFocusRequest = it.listFocusRequest + 1) }
        reloadTree()
        showNode(NodeSelection.Node(channelId))
        // 進み具合の表示は 1 つの更新のものなので、動いている更新があれば終わるのを待つ。
        updateJob?.join()
        launchUpdate(listOf(channelId), automatic = false)
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
        updateJob?.takeIf { it.isActive } ?: launchUpdate(channelIds = null, automatic = true)

    /** 次の定期更新までの待ち時間。前回の全体更新から [intervalMillis] が過ぎていれば 0。 */
    suspend fun millisUntilNextAutomaticUpdate(intervalMillis: Long): Long {
        val lastUpdatedAt = store.loadSetting(LAST_UPDATE_KEY)?.toLongOrNull() ?: return 0
        return (lastUpdatedAt + intervalMillis - now()).coerceIn(0, intervalMillis)
    }

    /**
     * 設定の間隔ごとに定期更新を続ける。呼び出したコルーチンが取り消されるまで戻らない。
     *
     * 間隔の設定が変わったら、新しい間隔で待ち直す。
     */
    suspend fun runAutomaticUpdates() {
        state.map { it.settings.updateIntervalMinutes * MILLIS_PER_MINUTE }.distinctUntilChanged().collectLatest { intervalMillis ->
            while (true) {
                delay(millisUntilNextAutomaticUpdate(intervalMillis))
                updateAutomatically().join()
                // 更新するチャンネルが無いうちは時刻を記録しないので、間を置いてから確かめ直す。
                if (store.loadSetting(LAST_UPDATE_KEY) == null) delay(RECHECK_WITHOUT_CHANNELS_MILLIS)
            }
        }
    }

    override fun openSettings() {
        mutableState.update { it.copy(dialog = DialogState.Settings) }
    }

    /** 設定を、取りうる範囲に収めてから反映して保存する。 */
    override fun submitSettings(settings: AppSettings) {
        val accepted = settings.validated()
        closeDialog()
        mutableState.update { it.copy(settings = accepted) }
        scope.launch { store.saveSettings(accepted.toSettings()) }
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

    private fun startOrCancelUpdate(channelIds: Collection<Long>?) {
        updateJob?.takeIf { it.isActive }?.let { running ->
            running.cancel()
            return
        }
        launchUpdate(channelIds, automatic = false)
    }

    /** [channelIds] のチャンネルを更新する。null なら全てのチャンネルを更新する。 */
    private fun launchUpdate(channelIds: Collection<Long>?, automatic: Boolean): Job = scope.launch {
        val allChannels = channelIds == null
        mutableState.update { it.copy(statusMessage = null) }
        try {
            val result = updater.update(channelIds)
            mutableState.update { it.copy(statusMessage = summaryOf(result)) }
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(statusMessage = UPDATE_CANCELLED_MESSAGE) }
            throw cancellation
        } finally {
            withContext(NonCancellable) {
                // 中止した場合も次の定期更新を待たせるため、全体更新を試みた時刻として記録する。
                // 更新するチャンネルが無かったときは記録せず、チャンネルが登録されたらすぐ更新できるようにする。
                if (allChannels && store.loadUpdateTargets(channelIds = null).isNotEmpty()) {
                    store.saveSetting(LAST_UPDATE_KEY, now().toString())
                }
                // 中止した場合も、そこまでに取り込んだ分を画面に反映する。
                if (automatic && windowVisible) reloadTree() else reload()
            }
        }
    }.also { updateJob = it }

    private fun summaryOf(result: UpdateProgress): String = buildString {
        append("更新しました  新着 ${result.newArticles} 件")
        if (result.failed > 0) append("  失敗 ${result.failed} 件")
    }

    private fun changeStates(articles: List<ArticleRow>, newState: ArticleState) {
        val changing = articles.filter { it.state != newState }.ifEmpty { return }
        val changingIds = changing.map { it.id }.toSet()
        fun changed(article: ArticleRow) = if (article.id in changingIds) article.copy(state = newState) else article
        loadedArticles = loadedArticles.map(::changed)
        changing.forEach { article ->
            adjustCount(article.channelId, totalDelta = 0, unreadDelta = unreadWeight(newState) - unreadWeight(article.state))
        }
        // 読んでいる途中で行が動かないよう、並べ直さずにその行だけを差し替える。
        mutableState.update { state -> state.copy(articles = state.articles.map(::changed)).withTree() }
        scope.launch { store.setArticleStates(changingIds, newState) }
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
        treeRows = tree.rows(expanded, updater.updatingChannelIds.value),
        selectedNodeName = tree.nameOf(selectedNode),
        selectedNodeCount = tree.countOf(selectedNode),
        totalCount = tree.totalCount,
    )

    private fun MainUiState.withoutMarks(): MainUiState = copy(markedArticleIds = emptySet(), markRangeEndIndex = null)

    /** 一覧を作り直す。行の並びが変わるので選択と印は外す。 */
    private fun MainUiState.withArrangedArticles(): MainUiState {
        contentJob?.cancel()
        return copy(
            articles = arrangeArticles(loadedArticles, filter, sort, channelNames),
            selectedArticleIndex = null,
            articleContent = null,
        ).withoutMarks()
    }

    private companion object {
        const val UPDATE_CANCELLED_MESSAGE = "更新を中止しました"
        const val UNEXPECTED_ERROR_MESSAGE = "エラーが発生しました"
        const val CHOOSE_FEED_MESSAGE = "登録するフィードを選んでください"
        const val DUPLICATE_FEED_MESSAGE = "このフィードは登録済みです。もう一度「追加」を押すと重ねて登録します"

        const val MILLIS_PER_MINUTE = 60 * 1000L
        const val RECHECK_WITHOUT_CHANNELS_MILLIS = MILLIS_PER_MINUTE

        const val APP_NAME = "legirss"
        const val OPML_EXTENSION = ".opml"
        const val NOT_OPML_MESSAGE = "OPML として読めません"
        const val OPML_READ_FAILED_MESSAGE = "ファイルを読めません"
        const val OPML_WRITE_FAILED_MESSAGE = "ファイルに書けません"
        const val OPML_WRITTEN_MESSAGE = "OPML を書き出しました"

        /** ファイル名に使えない文字。 */
        val unsafeFileNameCharacters = Regex("""[\\/:*?"<>|]""")

        /** 全体の更新を最後に試みた時刻を保存する設定の名前。 */
        const val LAST_UPDATE_KEY = "last_update_at"
    }
}
