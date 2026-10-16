package to.sava.legirss.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import to.sava.legirss.data.DatabaseFactory
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.feed.FeedFetcher
import to.sava.legirss.feed.FeedLocator
import to.sava.legirss.model.AppSettings
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NewArticle
import to.sava.legirss.model.NewChannel
import to.sava.legirss.update.FeedUpdater
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * メイン画面を、操作を受ける状態で組み立てて、クリックやキーで動かしたときの振る舞いを確かめる。
 *
 * ウィンドウは出さない。メニューのショートカット（Space など）はウィンドウのメニューが受けるので、ここでは扱えない。
 */
@OptIn(ExperimentalTestApi::class)
class MainScreenUiTest {
    private val store = LegirssStore(DatabaseFactory.openInMemory(), Dispatchers.Unconfined)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

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

    /** 未読 2 件（older, newer）と既読 1 件（read）を持つチャンネルを登録した画面を作る。一覧は newer, older, read の順に並ぶ。 */
    private suspend fun loadedViewModel(): MainViewModel {
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
        val fetcher = FeedFetcher(FeedFetcher.createClient(MockEngine { respond("") }, timeoutMillis = 1_000))
        val reportError: (String, Throwable) -> Unit = { message, exception -> throw AssertionError(message, exception) }
        val updater = FeedUpdater(store, fetcher, Dispatchers.Unconfined, now = { 0L }, reportError = reportError)
        return MainViewModel(
            store = store,
            updater = updater,
            locator = FeedLocator(fetcher, Dispatchers.Unconfined),
            parentScope = scope,
            parseDispatcher = Dispatchers.Unconfined,
            now = { 0L },
            openUrl = {},
            copyText = {},
            opmlFiles = object : OpmlFiles {
                override suspend fun pick(): ByteArray? = null

                override suspend fun save(suggestedName: String, text: String): Boolean = false
            },
            reportError = reportError,
            initialSettings = AppSettings(),
        ).also(MainViewModel::load)
    }

    /**
     * クリックのときに押していることにする修飾キー。
     *
     * 一覧はクリックの修飾キーをウィンドウから読むが、テストの入力はウィンドウの修飾キーを変えないので、ここで渡す。
     */
    private var pressedModifiers = PointerKeyboardModifiers()

    private fun ComposeUiTest.showScreen(viewModel: MainViewModel) {
        setContent {
            val state by viewModel.state.collectAsState()
            val window = LocalWindowInfo.current
            val windowWithModifiers = object : WindowInfo by window {
                override val keyboardModifiers get() = pressedModifiers
            }
            CompositionLocalProvider(LocalWindowInfo provides windowWithModifiers) {
                MainScreen(state, viewModel, rememberScrollState(), ScreenLayout(), onLayoutChange = {})
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.articleRow(title: String): SemanticsNodeInteraction =
        onNode(hasText(title) and hasAnyAncestor(hasTestTag(ARTICLE_LIST_TEST_TAG)))

    private fun ComposeUiTest.treeRow(label: String): SemanticsNodeInteraction =
        onNode(hasText(label) and hasAnyAncestor(hasTestTag(TREE_TEST_TAG)))

    private fun ComposeUiTest.pressKey(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private val MainViewModel.selectedTitle get() = state.value.selectedArticle?.title

    private val MainViewModel.markedTitles
        get() = state.value.articles.filter { it.id in state.value.markedArticleIds }.map { it.title }

    /** ツリーのノードをクリックすると、そのノードが選ばれ、フォーカスは一覧に置かれる。続けて押した ↓ は一覧の先頭の記事を選ぶ。 */
    @Test
    fun keepsFocusOnListAfterTreeClick() = runComposeUiTest {
        val viewModel = loadedViewModel()
        showScreen(viewModel)
        val channel = viewModel.state.value.treeRows.single { it.label == CHANNEL_LABEL }.key

        treeRow(CHANNEL_LABEL).performClick()
        waitForIdle()
        pressKey(Key.DirectionDown)

        assertEquals(channel, viewModel.state.value.selectedNode)
        assertEquals("newer", viewModel.selectedTitle)
    }

    /** 一覧の行をクリックすると、その記事が選ばれて既読になる。画面の行の位置は動かない。 */
    @Test
    fun marksClickedArticleReadWithoutMovingRows() = runComposeUiTest {
        val viewModel = loadedViewModel()
        showScreen(viewModel)
        val titles = listOf("newer", "older", "read")
        val topsBefore = titles.map { articleRow(it).getBoundsInRoot().top }

        articleRow("older").performClick()
        waitForIdle()

        assertEquals("older", viewModel.selectedTitle)
        assertEquals(ArticleState.READ, viewModel.state.value.selectedArticle?.state)
        assertEquals(topsBefore, titles.map { articleRow(it).getBoundsInRoot().top })
    }

    /** Ctrl や Shift を押しながら行をクリックすると、印が付くだけで、印を付けた記事は既読にならない。表示している記事も変わらない。 */
    @Test
    fun marksRowsWithModifiersWithoutReading() = runComposeUiTest {
        val viewModel = loadedViewModel()
        showScreen(viewModel)
        articleRow("read").performClick()
        waitForIdle()

        val markedClickedRows = listOf(
            PointerKeyboardModifiers(isCtrlPressed = true) to "newer",
            PointerKeyboardModifiers(isShiftPressed = true) to "older",
        ).map { (modifiers, title) ->
            pressedModifiers = modifiers
            articleRow(title).performClick()
            waitForIdle()
            title in viewModel.markedTitles
        }

        assertEquals(listOf(true, true), markedClickedRows)
        assertEquals("read", viewModel.selectedTitle)
        assertEquals(
            listOf(ArticleState.UNREAD, ArticleState.UNREAD),
            viewModel.state.value.articles.filter { it.title != "read" }.map { it.state },
        )
    }

    /** F6 でフォーカスが一覧、本文、ツリーの順に移る。↓ はフォーカスのある領域だけが受ける。 */
    @Test
    fun movesFocusAcrossPanesWithF6() = runComposeUiTest {
        val viewModel = loadedViewModel()
        showScreen(viewModel)
        val channel = viewModel.state.value.treeRows.single { it.label == CHANNEL_LABEL }.key

        pressKey(Key.F6)
        pressKey(Key.DirectionDown)
        val selectedOnArticlePane = viewModel.selectedTitle
        pressKey(Key.F6)
        pressKey(Key.DirectionDown)
        val nodeOnTree = viewModel.state.value.selectedNode
        pressKey(Key.F6)
        pressKey(Key.DirectionDown)

        assertNull(selectedOnArticlePane)
        assertEquals(channel, nodeOnTree)
        assertEquals("newer", viewModel.selectedTitle)
    }

    private companion object {
        /** 登録したチャンネルのツリーでの表示。未読 2 件、全 3 件。 */
        const val CHANNEL_LABEL = "Example (2/3)"
    }
}
