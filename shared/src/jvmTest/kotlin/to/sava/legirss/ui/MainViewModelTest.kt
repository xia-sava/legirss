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
import to.sava.legirss.html.HtmlBlock
import to.sava.legirss.html.InlineText
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NewArticle
import to.sava.legirss.model.NewChannel
import to.sava.legirss.update.FeedUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val store = LegirssStore(DatabaseFactory.openInMemory(), Dispatchers.Unconfined)
    private var feedBody = "<rss version=\"2.0\"><channel><title>Feed</title></channel></rss>"

    private val openedUrls = mutableListOf<String>()

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
        val updater = FeedUpdater(store, fetcher, Dispatchers.Unconfined, now = { 1_000L })
        return MainViewModel(
            store = store,
            updater = updater,
            scope = backgroundScope,
            parseDispatcher = Dispatchers.Unconfined,
            now = { 1_000L },
            openUrl = openedUrls::add,
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

    /** 選んでいる記事の閲覧状況を、既読 → 保護 → 未読 の順と、その逆順に切り替えられる。 */
    @Test
    fun cyclesStateOfSelectedArticle() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)

        viewModel.cycleSelectedState(forward = true)
        val afterForward = viewModel.states[2]
        viewModel.cycleSelectedState(forward = true)
        val afterSecondForward = viewModel.states[2]
        viewModel.cycleSelectedState(forward = false)

        assertEquals(ArticleState.PROTECTED, afterForward)
        assertEquals(ArticleState.UNREAD, afterSecondForward)
        assertEquals(ArticleState.PROTECTED, viewModel.states[2])
        assertEquals(ArticleState.PROTECTED, storedStates()["read"])
    }

    /** 選んでいる記事を削除すると一覧と保存先から消え、選択は同じ位置に残る。 */
    @Test
    fun deletesSelectedArticle() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)

        viewModel.deleteSelectedArticle()

        assertEquals(listOf("newer", "older"), viewModel.titles)
        assertEquals(1, viewModel.state.value.selectedArticleIndex)
        assertEquals(ArticleCount(total = 2, unread = 2), viewModel.state.value.selectedNodeCount)
        assertEquals(setOf("newer", "older"), storedStates().keys)
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

    /** 更新すると新着が一覧に入り、結果がステータスに出る。一覧を並べ直すので選択は外れる。 */
    @Test
    fun reloadsAfterUpdate() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = loadedViewModel()
        viewModel.selectArticle(2)
        feedBody = "<rss version=\"2.0\"><channel><title>Feed</title>" +
            "<item><guid>fresh</guid><title>fresh</title></item></channel></rss>"

        viewModel.updateAll()
        // 取得の応答は別のスレッドで返るので、取り込んだ記事が一覧に出るまで待つ。
        viewModel.state.first { it.articles.size == 4 }

        assertEquals(listOf("fresh", "newer", "older", "read"), viewModel.titles)
        assertEquals("更新しました  新着 1 件", viewModel.state.value.statusMessage)
        assertNull(viewModel.state.value.selectedArticleIndex)
        assertNull(viewModel.state.value.updateProgress)
    }
}
