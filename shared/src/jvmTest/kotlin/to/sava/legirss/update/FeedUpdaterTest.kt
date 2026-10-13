package to.sava.legirss.update

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import to.sava.legirss.data.DatabaseFactory
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.feed.FeedFetcher
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.UpdateTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedUpdaterTest {
    private val feedUrl = "https://example.com/feed"
    private val store = LegirssStore(DatabaseFactory.openInMemory(), Dispatchers.Unconfined)
    private val requests = mutableListOf<HttpRequestData>()
    private val reportedErrors = mutableListOf<String>()
    private var clock = 1_000_000L

    private fun updater(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FeedUpdater {
        val engine = MockEngine { request ->
            requests.add(request)
            handler(request)
        }
        val fetcher = FeedFetcher(FeedFetcher.createClient(engine, timeoutMillis = 1_000))
        return FeedUpdater(store, fetcher, Dispatchers.Unconfined, now = { clock }, reportError = { message, _ -> reportedErrors.add(message) })
    }

    private suspend fun registerChannel(paused: Boolean = false, firstFetchAsRead: Boolean = false, feedUrl: String = this.feedUrl) {
        store.importNodes(
            listOf(
                NewChannel(
                    name = "Example",
                    feedUrl = feedUrl,
                    siteUrl = null,
                    title = null,
                    paused = paused,
                    firstFetchAsRead = firstFetchAsRead,
                    articles = emptyList(),
                ),
            ),
        )
    }

    private fun rss(vararg items: Pair<String, String>): String = buildString {
        append("<rss version=\"2.0\"><channel><title>Example Feed</title><link>https://example.com/</link>")
        items.forEach { (guid, title) -> append("<item><guid>$guid</guid><title>$title</title></item>") }
        append("</channel></rss>")
    }

    private suspend fun articles() = store.loadArticleRows(channelIds = null).sortedBy { it.id }

    private suspend fun channel() = store.loadTree().single()

    private suspend fun target(): UpdateTarget = store.loadUpdateTargets(channelIds = null).single()

    /** 取得した記事を未読として登録し、新着の数を報告する。フィードが名乗るタイトルとサイトの URL も保存する。 */
    @Test
    fun registersNewArticlesAsUnread() = runTest {
        registerChannel()

        val progress = updater { respond(rss("a" to "First", "b" to "Second")) }.update(channelIds = null)

        assertEquals(UpdateProgress(total = 1, finished = 1, newArticles = 2, failed = 0), progress)
        assertEquals(listOf("First", "Second"), articles().map { it.title })
        assertTrue(articles().all { it.state == ArticleState.UNREAD })
        assertEquals("Example Feed", channel().channel?.title)
        assertEquals("https://example.com/", channel().channel?.siteUrl)
    }

    /** 日時の無い記事は、フィードの中で先にあるものほど新しい側に並ぶ時刻を持つ。 */
    @Test
    fun ordersUndatedArticlesByFeedPosition() = runTest {
        registerChannel()

        updater { respond(rss("a" to "First", "b" to "Second")) }.update(channelIds = null)

        assertEquals(listOf(clock, clock - 1), articles().map { it.sortAt })
    }

    /** 登録済みの記事は増やさず、内容だけを新しくする。閲覧状況は変えない。 */
    @Test
    fun updatesContentOfKnownArticlesWithoutChangingState() = runTest {
        registerChannel()
        updater { respond(rss("a" to "First")) }.update(channelIds = null)
        store.setArticleState(articles().single().id, ArticleState.PROTECTED)

        val progress = updater { respond(rss("a" to "First, revised", "b" to "Second")) }.update(channelIds = null)

        assertEquals(1, progress.newArticles)
        assertEquals(listOf("First, revised", "Second"), articles().map { it.title })
        assertEquals(listOf(ArticleState.PROTECTED, ArticleState.UNREAD), articles().map { it.state })
    }

    /** 削除した記事は、フィードに残っていても登録し直さない。 */
    @Test
    fun doesNotRestoreDeletedArticles() = runTest {
        registerChannel()
        updater { respond(rss("a" to "First", "b" to "Second")) }.update(channelIds = null)
        store.deleteArticle(articles().first().id, now = clock)

        val progress = updater { respond(rss("a" to "First", "b" to "Second")) }.update(channelIds = null)

        assertEquals(0, progress.newArticles)
        assertEquals(listOf("Second"), articles().map { it.title })
    }

    private suspend fun setStates(vararg states: Pair<String, ArticleState>) {
        val ids = articles().associate { it.title to it.id }
        states.forEach { (title, state) -> store.setArticleState(ids.getValue(title), state) }
    }

    /** 既読の記事は新しい方から保存数だけ残す。未読と保護の記事は数に入れずに残す。消した記事は登録し直さない。 */
    @Test
    fun removesReadArticlesBeyondKeepCount() = runTest {
        registerChannel()
        store.saveSetting("keep_count", "2")
        val feed = rss("a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E", "f" to "F")
        updater { respond(feed) }.update(channelIds = null)
        setStates(
            "A" to ArticleState.READ,
            "B" to ArticleState.PROTECTED,
            "C" to ArticleState.READ,
            "D" to ArticleState.READ,
            "F" to ArticleState.READ,
        )

        val afterRemoval = updater { respond(feed) }.update(channelIds = null)
        val remaining = articles().map { it.title }
        val afterAnotherUpdate = updater { respond(feed) }.update(channelIds = null)

        assertEquals(listOf("A", "B", "C", "E"), remaining)
        assertEquals(0, afterRemoval.newArticles)
        assertEquals(0, afterAnotherUpdate.newArticles)
        assertEquals(remaining, articles().map { it.title })
    }

    /** 保存日数より古い既読の記事を消す。フィードに変更が無かった更新でも消す。未読と保護の記事は残す。 */
    @Test
    fun removesReadArticlesOlderThanKeepDays() = runTest {
        registerChannel()
        store.saveSetting("keep_days", "10")
        updater { respond(rss("a" to "A", "b" to "B", "c" to "C")) }.update(channelIds = null)
        setStates("A" to ArticleState.READ, "B" to ArticleState.PROTECTED)

        clock += 9 * GonePolicy.COUNT_INTERVAL_MILLIS
        updater { respond("", HttpStatusCode.NotModified) }.update(channelIds = null)
        val withinKeepDays = articles().map { it.title }
        clock += 2 * GonePolicy.COUNT_INTERVAL_MILLIS
        updater { respond("", HttpStatusCode.NotModified) }.update(channelIds = null)

        assertEquals(listOf("A", "B", "C"), withinKeepDays)
        assertEquals(listOf("B", "C"), articles().map { it.title })
    }

    /** 削除した記事は、フィードに載り続けている限り、長い時間が経っても登録し直さない。 */
    @Test
    fun keepsDeletedArticlesForgottenWhileTheyStayInFeed() = runTest {
        registerChannel()
        updater { respond(rss("a" to "A", "b" to "B")) }.update(channelIds = null)
        store.deleteArticle(articles().first().id, now = clock)

        repeat(3) {
            clock += 20 * GonePolicy.COUNT_INTERVAL_MILLIS
            updater { respond(rss("a" to "A", "b" to "B")) }.update(channelIds = null)
        }

        assertEquals(listOf("B"), articles().map { it.title })
    }

    /** 削除した記事の控えは、フィードで 30 日見かけなくなったら捨てる。そのあとに再び載った記事は新着として登録する。 */
    @Test
    fun forgetsDeletedArticlesLongAfterTheyLeaveFeed() = runTest {
        registerChannel()
        updater { respond(rss("a" to "A", "b" to "B")) }.update(channelIds = null)
        store.deleteArticle(articles().first().id, now = clock)

        clock += 31 * GonePolicy.COUNT_INTERVAL_MILLIS
        updater { respond(rss("b" to "B")) }.update(channelIds = null)
        val progress = updater { respond(rss("a" to "A", "b" to "B")) }.update(channelIds = null)

        assertEquals(1, progress.newArticles)
        assertEquals(listOf("B", "A"), articles().map { it.title })
    }

    /** 「最初の取得は既読」の指定があるチャンネルは、最初の取得分を既読で登録し、新着に数えない。次の取得からは未読になる。 */
    @Test
    fun registersFirstFetchAsReadWhenRequested() = runTest {
        registerChannel(firstFetchAsRead = true)

        val first = updater { respond(rss("a" to "Old")) }.update(channelIds = null)
        val second = updater { respond(rss("a" to "Old", "b" to "New")) }.update(channelIds = null)

        assertEquals(0, first.newArticles)
        assertEquals(1, second.newArticles)
        assertEquals(listOf(ArticleState.READ, ArticleState.UNREAD), articles().map { it.state })
    }

    /** 応答の ETag と Last-Modified を保存し、次の取得で送る。304 が返れば記事は変えない。 */
    @Test
    fun revalidatesWithSavedValidators() = runTest {
        registerChannel()
        val headers = headersOf(
            HttpHeaders.ETag to listOf("\"v1\""),
            HttpHeaders.LastModified to listOf("Fri, 14 Aug 2026 09:30:29 GMT"),
        )
        updater { respond(rss("a" to "First"), headers = headers) }.update(channelIds = null)

        val progress = updater { respond("", HttpStatusCode.NotModified) }.update(channelIds = null)

        assertEquals(UpdateProgress(total = 1, finished = 1, newArticles = 0, failed = 0), progress)
        assertEquals("\"v1\"", requests.last().headers[HttpHeaders.IfNoneMatch])
        assertEquals("Fri, 14 Aug 2026 09:30:29 GMT", requests.last().headers[HttpHeaders.IfModifiedSince])
        assertEquals(1, articles().size)
    }

    /** 恒久的に移転していたら、フィードの URL を移転先に書き換える。 */
    @Test
    fun rewritesFeedUrlOnPermanentRedirect() = runTest {
        registerChannel()

        updater { request ->
            if (request.url.encodedPath == "/feed") {
                respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/moved"))
            } else {
                respond(rss("a" to "First"))
            }
        }.update(channelIds = null)

        assertEquals("https://example.com/moved", channel().channel?.feedUrl)
    }

    /** 取得の失敗と、フィードとして読めない応答を、チャンネルのエラーとして記録する。次に成功すればエラーは消える。 */
    @Test
    fun recordsFailureOnChannel() = runTest {
        registerChannel()

        val httpError = updater { respondError(HttpStatusCode.InternalServerError) }.update(channelIds = null)
        val errorAfterHttpError = channel().channel?.hasError
        val notAFeed = updater { respond("<html><body>maintenance</body></html>") }.update(channelIds = null)
        val errorAfterNotAFeed = channel().channel?.hasError
        updater { respond(rss("a" to "First")) }.update(channelIds = null)

        assertEquals(1, httpError.failed)
        assertEquals(true, errorAfterHttpError)
        assertEquals(1, notAFeed.failed)
        assertEquals(true, errorAfterNotAFeed)
        assertEquals(false, channel().channel?.hasError)
    }

    /** 1 つのチャンネルの更新で想定していない例外が出ても、失敗として数えて記録し、ほかのチャンネルは更新する。 */
    @Test
    fun continuesAfterUnexpectedFailureOfOneChannel() = runTest {
        registerChannel(feedUrl = "https://broken.example/feed")
        registerChannel()

        val progress = updater { request ->
            check(request.url.host != "broken.example") { "unexpected" }
            respond(rss("a" to "First"))
        }.update(channelIds = null)

        assertEquals(UpdateProgress(total = 2, finished = 2, newArticles = 1, failed = 1), progress)
        assertEquals(listOf("チャンネルを更新できません: https://broken.example/feed"), reportedErrors)
        assertEquals(listOf("First"), articles().map { it.title })
    }

    /** 購読中止のチャンネルと、指定の範囲に入らないチャンネルは取得しない。 */
    @Test
    fun skipsPausedAndUnselectedChannels() = runTest {
        registerChannel(paused = true)

        val paused = updater { respond(rss("a" to "First")) }.update(channelIds = null)
        store.importNodes(
            listOf(NewChannel("Other", "https://other.example/feed", null, null, paused = false, firstFetchAsRead = false, articles = emptyList())),
        )
        val unselected = updater { respond(rss("a" to "First")) }.update(channelIds = listOf(-1L))

        assertEquals(0, paused.total)
        assertEquals(0, unselected.total)
        assertTrue(requests.isEmpty())
    }

    /** 404 が間隔を空けて規定の回数続いたら購読中止にする。間隔の短い 404 は数えない。 */
    @Test
    fun pausesChannelAfterRepeatedGoneResponses() = runTest {
        registerChannel()
        val updater = updater { respondError(HttpStatusCode.NotFound) }

        updater.update(channelIds = null)
        updater.update(channelIds = null)
        val countAfterQuickRetry = target().goneCount
        repeat(GonePolicy.PAUSE_THRESHOLD - 2) {
            clock += GonePolicy.COUNT_INTERVAL_MILLIS
            updater.update(channelIds = null)
        }
        val pausedBeforeThreshold = channel().channel?.paused
        clock += GonePolicy.COUNT_INTERVAL_MILLIS
        updater.update(channelIds = null)

        assertEquals(1, countAfterQuickRetry)
        assertEquals(false, pausedBeforeThreshold)
        assertEquals(true, channel().channel?.paused)
    }

    /** 成功すれば、無くなった応答の数え直しになる。 */
    @Test
    fun resetsGoneCountOnSuccess() = runTest {
        registerChannel()
        updater { respondError(HttpStatusCode.Gone) }.update(channelIds = null)

        updater { respond(rss("a" to "First")) }.update(channelIds = null)

        assertEquals(0, target().goneCount)
        assertNull(target().goneAt)
    }

    /** 更新していないときの進み具合は null になる。 */
    @Test
    fun clearsProgressWhenIdle() = runTest {
        registerChannel()
        val updater = updater { respond(rss("a" to "First")) }

        updater.update(channelIds = null)

        assertNull(updater.progress.value)
        assertFalse(articles().isEmpty())
    }
}
