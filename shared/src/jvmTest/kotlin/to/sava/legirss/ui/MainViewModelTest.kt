package to.sava.legirss.ui

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import to.sava.legirss.data.DatabaseFactory
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.feed.FeedFetcher
import to.sava.legirss.feed.FeedLocator
import to.sava.legirss.html.HtmlBlock
import to.sava.legirss.html.InlineText
import to.sava.legirss.model.AppSettings
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NewArticle
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.Retention
import to.sava.legirss.opml.Opml
import to.sava.legirss.ui.component.TreeDropPlace
import to.sava.legirss.update.FeedUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val store = LegirssStore(DatabaseFactory.openInMemory(), Dispatchers.Unconfined)
    private var feedBody = "<rss version=\"2.0\"><channel><title>Feed</title></channel></rss>"

    private val openedUrls = mutableListOf<String>()
    private val copiedTexts = mutableListOf<String>()

    /** 読み込むファイルとして返す中身。null なら、利用者が選ばずに閉じたものとして扱う。 */
    private var opmlToPick: String? = null

    /** 書き出された OPML。ファイル名と中身の組。 */
    private val savedOpml = mutableListOf<Pair<String, String>>()
    private val opmlFiles = object : OpmlFiles {
        override suspend fun pick(): ByteArray? = opmlToPick?.encodeToByteArray()

        override suspend fun save(suggestedName: String, text: String): Boolean = savedOpml.add(suggestedName to text)
    }
    private var clock = 1_000L

    /** null でなければ、時刻を尋ねられたときにこれを投げる。 */
    private var clockFailure: Exception? = null
    private val reportedErrors = mutableListOf<String>()

    private fun article(title: String, state: ArticleState, sortAt: Long) = NewArticle(
        identity = title,
        title = title,
        link = "https://example.com/$title",
        summary = "<p>summary of $title</p>",
        content = null,
        author = null,
        category = null,
        publishedAt = null,
        sortAt = sortAt,
        fetchedAt = sortAt,
        state = state,
    )

    /** 未読 2 件（older, newer）と既読 1 件（read）を持つチャンネルを登録した画面を作る。 */
    private suspend fun TestScope.loadedViewModel(): MainViewModel {
        store.importNodes(
            listOf(
                NewChannel(
                    name = "Example",
                    feedUrl = "https://example.com/feed",
                    siteUrl = null,
                    title = null,
                    paused = false,
                    firstFetchAsRead = false,
                    articles = listOf(
                        article("older", ArticleState.UNREAD, sortAt = 100),
                        article("newer", ArticleState.UNREAD, sortAt = 200),
                        article("read", ArticleState.READ, sortAt = 300),
                    ),
                ),
            ),
        )
        val fetcher = FeedFetcher(FeedFetcher.createClient(MockEngine { respond(feedBody) }, timeoutMillis = 1_000))
        val reportError: (String, Throwable) -> Unit = { message, _ -> reportedErrors.add(message) }
        val updater = FeedUpdater(store, fetcher, Dispatchers.Unconfined, now = { clock }, reportError = reportError)
        return MainViewModel(
            store = store,
            updater = updater,
            locator = FeedLocator(fetcher, Dispatchers.Unconfined),
            parentScope = backgroundScope,
            parseDispatcher = Dispatchers.Unconfined,
            now = { clockFailure?.let { throw it } ?: clock },
            openUrl = openedUrls::add,
            copyText = copiedTexts::add,
            opmlFiles = opmlFiles,
            reportError = reportError,
            initialSettings = AppSettings(),
        ).also(MainViewModel::load)
    }

    /** 記事を選ぶと本文を読み出して表示用のブロックにする。一覧を並べ直すと表示は消える。 */
    @Test
    fun showsContentOfSelectedArticle() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.selectArticle(1)
        val shown = viewModel.state.value.articleContent
        viewModel.cycleFilter()

        assertEquals(listOf(HtmlBlock.Paragraph(InlineText("summary of older"))), shown?.blocks)
        assertEquals(viewModel.state.value.articles.size, 1)
        assertNull(viewModel.state.value.articleContent)
    }

    /** 選んでいる記事のリンクを外部のブラウザへ渡す。記事を選んでいなければ何もしない。 */
    @Test
    fun opensLinkOfSelectedArticle() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.openSelectedArticle()
        val openedWithoutSelection = openedUrls.toList()
        viewModel.selectArticle(0)
        viewModel.openSelectedArticle()

        assertEquals(emptyList(), openedWithoutSelection)
        assertEquals(listOf("https://example.com/newer"), openedUrls)
    }

    private val MainViewModel.titles get() = state.value.articles.map { it.title }

    private val MainViewModel.states get() = state.value.articles.map { it.state }

    private suspend fun storedStates() = store.loadArticleRows(channelIds = null).associate { it.title to it.state }

    /** 読み込むと「全ての記事」を選んだ状態になり、未読が先・新しい順で並ぶ。記事は選ばれていない。 */
    @Test
    fun showsAllArticlesUnreadFirst() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        assertEquals(listOf("newer", "older", "read"), viewModel.titles)
        assertEquals(ArticleCount(total = 3, unread = 2), viewModel.state.value.selectedNodeCount)
        assertNull(viewModel.state.value.selectedArticleIndex)
    }

    /** 未読の記事を選ぶと既読になり保存される。行の並びは変わらず、未読数が減る。 */
    @Test
    fun marksArticleReadOnSelection() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.selectArticle(0)

        assertEquals(listOf("newer", "older", "read"), viewModel.titles)
        assertEquals(listOf(ArticleState.READ, ArticleState.UNREAD, ArticleState.READ), viewModel.states)
        assertEquals(ArticleCount(total = 3, unread = 1), viewModel.state.value.selectedNodeCount)
        assertEquals(ArticleState.READ, storedStates()["newer"])
    }

    /** 読み進めると一番下の未読を選び、続けると残った未読を下から順にたどる。未読が無くなったら動かない。 */
    @Test
    fun readsOnFromLastUnreadUpwards() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.readOn()
        val first = viewModel.state.value.selectedArticle?.title
        viewModel.readOn()
        val second = viewModel.state.value.selectedArticle?.title
        viewModel.readOn()

        assertEquals("older", first)
        assertEquals("newer", second)
        assertEquals(0, viewModel.state.value.selectedArticleIndex)
        assertEquals(ArticleCount(total = 3, unread = 0), viewModel.state.value.selectedNodeCount)
    }

    /** 選んでいる行より下に未読が残っていれば、読み進めたときにそちらへ移る。 */
    @Test
    fun readsOnToUnreadBelowSelection() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(0)

        viewModel.readOn()

        assertEquals("older", viewModel.state.value.selectedArticle?.title)
        assertEquals(1, viewModel.state.value.selectedArticleIndex)
    }

    /** 読み進めるときは既読の行を通らず、離れた位置にある未読へ直接移る。 */
    @Test
    fun readsOnSkippingReadRows() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(1)
        viewModel.selectArticle(2)

        viewModel.readOn()

        assertEquals("newer", viewModel.state.value.selectedArticle?.title)
        assertEquals(0, viewModel.state.value.selectedArticleIndex)
    }

    /** 選んでいる記事の未読と既読を切り替えられる。切り替えた結果は保存される。 */
    @Test
    fun togglesStateOfSelectedArticle() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)

        viewModel.toggleSelectedState()
        val afterFirstToggle = viewModel.states[2]
        val storedAfterFirstToggle = storedStates()["read"]
        viewModel.toggleSelectedState()

        assertEquals(ArticleState.UNREAD, afterFirstToggle)
        assertEquals(ArticleState.UNREAD, storedAfterFirstToggle)
        assertEquals(ArticleState.READ, viewModel.states[2])
        assertEquals(ArticleState.READ, storedStates()["read"])
    }

    /** 選んでいる記事を削除すると一覧と保存先から消え、選択は同じ位置に残る。 */
    @Test
    fun deletesSelectedArticle() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)

        viewModel.deleteSelectedArticles()

        assertEquals(listOf("newer", "older"), viewModel.titles)
        assertEquals(1, viewModel.state.value.selectedArticleIndex)
        assertEquals(ArticleCount(total = 2, unread = 2), viewModel.state.value.selectedNodeCount)
        assertEquals(setOf("newer", "older"), storedStates().keys)
    }

    /** 操作の処理で想定していない例外が出たら、記録してステータスに出す。以後の操作は続けられる。 */
    @Test
    fun reportsUnexpectedFailureAndKeepsWorking() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)
        clockFailure = IllegalStateException("unexpected")

        viewModel.deleteSelectedArticles()
        val messageAfterFailure = viewModel.state.value.statusMessage
        clockFailure = null
        viewModel.selectArticle(0)

        assertEquals("エラーが発生しました", messageAfterFailure)
        assertEquals(listOf("エラーが発生しました"), reportedErrors)
        assertEquals(ArticleState.READ, storedStates()["newer"])
    }

    private val MainViewModel.markedTitles
        get() = state.value.articles.filter { it.id in state.value.markedArticleIds }.map { it.title }

    /** 範囲で印を付けても、表示している記事は変わらず、印を付けた記事は既読にならない。記事を選び直すと印は外れる。 */
    @Test
    fun marksRangeWithoutReadingIt() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)

        viewModel.markArticlesTo(0)
        val marked = viewModel.markedTitles
        val statesWhileMarked = viewModel.states
        val shownWhileMarked = viewModel.state.value.selectedArticle?.title
        viewModel.selectArticle(2)

        assertEquals(listOf("newer", "older", "read"), marked)
        assertEquals(listOf(ArticleState.UNREAD, ArticleState.UNREAD, ArticleState.READ), statesWhileMarked)
        assertEquals("read", shownWhileMarked)
        assertEquals(emptyList(), viewModel.markedTitles)
    }

    /** 印の付け外しは 1 件ずつでき、最初の 1 つを付けるときは表示している記事にも印が付く。全てに付けることもできる。 */
    @Test
    fun togglesMarksOneByOne() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)

        viewModel.toggleArticleMark(0)
        val afterFirstToggle = viewModel.markedTitles
        viewModel.toggleArticleMark(2)
        val afterSecondToggle = viewModel.markedTitles
        viewModel.markAllArticles()

        assertEquals(listOf("newer", "read"), afterFirstToggle)
        assertEquals(listOf("newer"), afterSecondToggle)
        assertEquals(listOf("newer", "older", "read"), viewModel.markedTitles)
    }

    /** 印を付けた記事があれば、状況の変更・コピー・削除はそれら全てに及ぶ。削除すると印は外れる。 */
    @Test
    fun appliesOperationsToMarkedArticles() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)
        viewModel.toggleArticleMark(1)

        viewModel.setSelectedState(ArticleState.READ)
        val statesAfterChange = storedStates()
        viewModel.copySelected(CopyContent.TITLE_AND_URL)
        viewModel.deleteSelectedArticles()

        assertEquals(ArticleState.READ, statesAfterChange["older"])
        assertEquals(ArticleState.READ, statesAfterChange["read"])
        assertEquals(ArticleState.UNREAD, statesAfterChange["newer"])
        assertEquals(listOf("older https://example.com/older\nread https://example.com/read"), copiedTexts)
        assertEquals(listOf("newer"), viewModel.titles)
        assertEquals(setOf("newer"), storedStates().keys)
        assertEquals(emptyList(), viewModel.markedTitles)
        assertEquals(0, viewModel.state.value.selectedArticleIndex)
    }

    /** 印を付けていなければ、コピーは表示している記事だけを対象にする。記事を選んでいなければ何も写さない。 */
    @Test
    fun copiesUrlOfShownArticle() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.copySelected(CopyContent.URL)
        val copiedWithoutSelection = copiedTexts.toList()
        viewModel.selectArticle(0)
        viewModel.copySelected(CopyContent.URL)

        assertEquals(emptyList(), copiedWithoutSelection)
        assertEquals(listOf("https://example.com/newer"), copiedTexts)
    }

    /** 一覧を並べ直す操作（ノードの選択など）は表示を移す合図を出し、記事を選ぶだけの操作は出さない。 */
    @Test
    fun signalsListResetOnlyWhenRearranged() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        val initial = viewModel.state.value.listArrangement

        viewModel.selectArticle(0)
        viewModel.toggleSelectedState()
        val afterReading = viewModel.state.value.listArrangement
        viewModel.selectNode(NodeSelection.Node(store.loadTree().single().id))

        assertEquals(initial, afterReading)
        assertEquals(initial + 1, viewModel.state.value.listArrangement)
    }

    /** 絞り込みを切り替えると、その状況の記事だけが並ぶ。 */
    @Test
    fun cyclesFilter() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.cycleFilter()
        val unreadOnly = viewModel.titles
        viewModel.cycleFilter()

        assertEquals(listOf("newer", "older"), unreadOnly)
        assertEquals(StateFilter.READ, viewModel.state.value.filter)
        assertEquals(listOf("read"), viewModel.titles)
    }

    private val freshFeed = "<rss version=\"2.0\"><channel><title>Feed</title>" +
        "<item><guid>fresh</guid><title>fresh</title></item></channel></rss>"

    /** ウィンドウを表示している間の定期更新は、一覧の行と選択を動かさず、ツリーの記事数とステータスのメッセージだけを更新する。 */
    @Test
    fun keepsListDuringAutomaticUpdateWhileVisible() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)
        feedBody = freshFeed

        viewModel.updateAutomatically().join()

        assertEquals(listOf("newer", "older", "read"), viewModel.titles)
        assertEquals(2, viewModel.state.value.selectedArticleIndex)
        assertEquals(ArticleCount(total = 4, unread = 3), viewModel.state.value.selectedNodeCount)
        assertEquals("更新しました  新着 1 件", viewModel.state.value.statusMessage)
    }

    /** ウィンドウをしまっている間の定期更新では、全体の未読数に新着が加わる。表示すると一覧が並べ直され、フォーカスの要求が出る。 */
    @Test
    fun rearrangesListWhenShownAfterUpdateWhileHidden() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.setWindowVisible(false)
        feedBody = freshFeed

        viewModel.updateAutomatically().join()
        val unreadWhileHidden = viewModel.state.value.totalCount.unread
        val focusRequestBefore = viewModel.state.value.listFocusRequest
        viewModel.setWindowVisible(true)

        assertEquals(3, unreadWhileHidden)
        assertEquals(focusRequestBefore + 1, viewModel.state.value.listFocusRequest)
        assertEquals(listOf("fresh", "newer", "older", "read"), viewModel.titles)
        assertNull(viewModel.state.value.selectedArticleIndex)
    }

    /** 次の定期更新までの待ち時間は、全体更新をしたことが無ければ 0 で、更新した後は経過した分だけ間隔から減る。 */
    @Test
    fun computesDelayUntilNextAutomaticUpdate() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        val interval = 60_000L

        val beforeAnyUpdate = viewModel.millisUntilNextAutomaticUpdate(interval)
        viewModel.updateAutomatically().join()
        val rightAfterUpdate = viewModel.millisUntilNextAutomaticUpdate(interval)
        clock += 45_000
        val later = viewModel.millisUntilNextAutomaticUpdate(interval)
        clock += 60_000
        val overdue = viewModel.millisUntilNextAutomaticUpdate(interval)

        assertEquals(0, beforeAnyUpdate)
        assertEquals(interval, rightAfterUpdate)
        assertEquals(15_000, later)
        assertEquals(0, overdue)
    }

    /** URL を指定してチャンネルを追加すると、ツリーに登録されて選ばれ、取得した記事が一覧に出る。ダイアログは閉じる。 */
    @Test
    fun addsChannelFromFeedUrl() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        feedBody = freshFeed
        viewModel.openAddChannel()

        viewModel.submitAddChannel("https://example.com/another")
        // 取得の応答は別のスレッドで返るので、登録したチャンネルの記事が一覧に出るまで待つ。
        viewModel.state.first { it.titlesAre("fresh") }

        val added = store.loadTree().single { it.channel?.feedUrl == "https://example.com/another" }
        assertEquals("Feed", added.name)
        assertEquals(NodeSelection.Node(added.id), viewModel.state.value.selectedNode)
        assertNull(viewModel.state.value.dialog)
    }

    private fun MainUiState.titlesAre(vararg titles: String) = articles.map { it.title } == titles.toList()

    /** 登録済みのフィードを指定されたら一度知らせて登録せず、同じフィードをもう一度指定されたら登録する。 */
    @Test
    fun asksBeforeAddingDuplicateFeed() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.openAddChannel()

        viewModel.submitAddChannel("https://example.com/feed")
        val asked = viewModel.state.first { (it.dialog as? DialogState.AddChannel)?.duplicateUrl != null }
        val channelsWhenAsked = store.loadTree().size
        viewModel.submitAddChannel("https://example.com/feed")
        viewModel.state.first { it.dialog == null }

        assertEquals("このフィードは登録済みです。もう一度「追加」を押すと重ねて登録します", (asked.dialog as DialogState.AddChannel).message)
        assertEquals(1, channelsWhenAsked)
        assertEquals(2, store.loadTree().size)
    }

    /** 購読中止にしたチャンネルは更新の対象から外れ、再開すると対象に戻る。 */
    @Test
    fun pausesAndResumesChannel() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        val channel = NodeSelection.Node(store.loadTree().single().id)

        viewModel.setNodePaused(channel, paused = true)
        val targetsWhilePaused = store.loadUpdateTargets(channelIds = null).size
        viewModel.setNodePaused(channel, paused = false)

        assertEquals(0, targetsWhilePaused)
        assertEquals(1, store.loadUpdateTargets(channelIds = null).size)
    }

    private val MainViewModel.treeLabels get() = state.value.treeRows.map { it.label }

    /** グループを追加し、名前を変えると、ツリーの表示に反映される。入力の前後の空白は除く。 */
    @Test
    fun addsAndRenamesGroup() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.openAddGroup()
        viewModel.submitText("  News ")
        val afterAdd = viewModel.treeLabels
        viewModel.openRename(NodeSelection.Node(store.loadTree().single { it.name == "News" }.id))
        val initialText = (viewModel.state.value.dialog as DialogState.EditText).initialText
        viewModel.submitText("Daily")

        assertEquals(listOf("全ての記事 (2/3)", "Example (2/3)", "News"), afterAdd)
        assertEquals("News", initialText)
        assertEquals(listOf("全ての記事 (2/3)", "Example (2/3)", "Daily"), viewModel.treeLabels)
        assertNull(viewModel.state.value.dialog)
    }

    /** 削除は確認のダイアログを経て行い、確認するとノードと記事が消えて、親にあたるノードが選ばれる。 */
    @Test
    fun deletesNodeAfterConfirmation() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        val channel = NodeSelection.Node(store.loadTree().single().id)
        viewModel.selectNode(channel)

        viewModel.requestDelete(channel)
        val nodesWhileAsking = store.loadTree().size
        viewModel.confirmDelete()

        assertEquals(1, nodesWhileAsking)
        assertEquals(listOf("全ての記事"), viewModel.treeLabels)
        assertEquals(NodeSelection.All, viewModel.state.value.selectedNode)
        assertEquals(emptyList(), viewModel.titles)
    }

    /** ノードをグループの行の中へ移すと、そのグループが開いて、中に移したノードが並ぶ。 */
    @Test
    fun movesNodeIntoGroup() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        val channel = NodeSelection.Node(store.loadTree().single().id)
        val group = NodeSelection.Node(store.addGroup(parentId = null, name = "News"))
        viewModel.reloadList()

        viewModel.moveNode(channel, group, TreeDropPlace.INTO)

        assertEquals(listOf("全ての記事 (2/3)", "News (2/3)", "Example (2/3)"), viewModel.treeLabels)
        assertEquals(listOf(0, 1, 2), viewModel.state.value.treeRows.map { it.depth })
    }

    /** 設定を決めると、範囲に収めた値が画面の状態に反映され、保存される。保存した値は次の更新での記事の整理に使われる。 */
    @Test
    fun appliesAndSavesSettings() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.openSettings()

        viewModel.submitSettings(AppSettings(listFontSize = 100, updateIntervalMinutes = 5, retention = Retention(keepCount = 20, keepDays = 7)))

        val expected = AppSettings(listFontSize = 32, updateIntervalMinutes = 30, retention = Retention(keepCount = 20, keepDays = 7))
        assertEquals(expected, viewModel.state.value.settings)
        assertEquals(expected, AppSettings.fromSettings(store.loadSettings()))
        assertEquals(Retention(keepCount = 20, keepDays = 7), store.loadRetention())
        assertNull(viewModel.state.value.dialog)
    }

    /** OPML を読み込むと、登録済みのフィードを除いたチャンネルとグループが最上位の末尾に入り、件数がステータスに出る。 */
    @Test
    fun importsOpmlSkippingKnownFeeds() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        opmlToPick = "<opml version=\"2.0\"><body>" +
            "<outline text=\"Known\" xmlUrl=\"https://example.com/feed\"/>" +
            "<outline text=\"News\"><outline text=\"Alpha\" xmlUrl=\"https://alpha.example/feed\"/></outline>" +
            "</body></opml>"

        viewModel.importOpml()

        assertEquals(listOf("全ての記事 (2/3)", "Example (2/3)", "News"), viewModel.treeLabels)
        assertEquals(listOf("Example", "News", "Alpha"), store.loadTree().map { it.name })
        assertEquals("OPML を読み込みました  チャンネル 1 件", viewModel.state.value.statusMessage)
    }

    /** OPML でないファイルを選ぶと、何も取り込まずに、読めなかったことをステータスに出す。 */
    @Test
    fun rejectsFileThatIsNotOpml() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        opmlToPick = "<html><body>page</body></html>"

        viewModel.importOpml()

        assertEquals(1, store.loadTree().size)
        assertEquals("OPML として読めません", viewModel.state.value.statusMessage)
    }

    /** 全体を書き出すと、登録しているチャンネルを含む OPML が、アプリの名前のファイル名で保存先へ渡る。 */
    @Test
    fun exportsWholeTreeAsOpml() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()

        viewModel.exportOpml(NodeSelection.All)

        val (name, text) = savedOpml.single()
        assertEquals("legirss.opml", name)
        assertEquals("https://example.com/feed", (Opml.parse(text)?.single() as NewChannel).feedUrl)
        assertEquals("OPML を書き出しました", viewModel.state.value.statusMessage)
    }

    /** フィードの URL を変えると、次の更新は新しい URL から取得する。 */
    @Test
    fun changesFeedUrl() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        val channel = NodeSelection.Node(store.loadTree().single().id)

        viewModel.openChangeFeedUrl(channel)
        viewModel.submitText("https://example.com/moved")

        assertEquals("https://example.com/moved", store.loadUpdateTargets(channelIds = null).single().feedUrl)
    }

    /** 更新するチャンネルが無い全体更新は、更新した時刻として記録しない。チャンネルができたら待たずに更新できる。 */
    @Test
    fun doesNotRecordUpdateWithoutChannels() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.setNodePaused(NodeSelection.Node(store.loadTree().single().id), paused = true)

        viewModel.updateAutomatically().join()

        assertEquals(0, viewModel.millisUntilNextAutomaticUpdate(intervalMillis = 60_000))
    }

    /** 更新すると新着が一覧に入り、結果がステータスに出る。一覧を並べ直すので選択は外れる。 */
    @Test
    fun reloadsAfterUpdate() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)
        feedBody = freshFeed

        viewModel.updateAll()
        // 取得の応答は別のスレッドで返るので、取り込んだ記事が一覧に出るまで待つ。
        viewModel.state.first { it.articles.size == 4 }

        assertEquals(listOf("fresh", "newer", "older", "read"), viewModel.titles)
        assertEquals("更新しました  新着 1 件", viewModel.state.value.statusMessage)
        assertNull(viewModel.state.value.selectedArticleIndex)
        assertNull(viewModel.state.value.updateProgress)
    }
}
