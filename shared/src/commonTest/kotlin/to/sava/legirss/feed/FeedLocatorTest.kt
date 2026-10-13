package to.sava.legirss.feed

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeedLocatorTest {
    private val feed = "<rss version=\"2.0\"><channel><title>Example Feed</title></channel></rss>"

    private fun page(vararg links: String): String = "<html><head>${links.joinToString("")}</head><body>page</body></html>"

    private fun rssLink(href: String, title: String = "") =
        "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"$href\" title=\"$title\">"

    /** 模擬の応答を、URL のパスごとに決めて探す。応答を決めていないパスは 404 を返す。 */
    private suspend fun locate(input: String, responses: Map<String, String>): FeedLookup {
        val engine = MockEngine { request ->
            responses[request.url.encodedPath]?.let { respond(it) } ?: respondError(HttpStatusCode.NotFound)
        }
        return FeedLocator(FeedFetcher(FeedFetcher.createClient(engine, timeoutMillis = 1_000)), Dispatchers.Unconfined).locate(input)
    }

    /** 入力された URL がフィードなら、その URL とフィードが名乗るタイトルを返す。 */
    @Test
    fun findsFeedAtGivenUrl() = runTest {
        val lookup = locate("https://example.com/feed", mapOf("/feed" to feed))

        assertEquals(FeedLookup.Found("https://example.com/feed", "Example Feed"), lookup)
    }

    /** スキームを省いた入力は https の URL として扱う。前後の空白は無視する。 */
    @Test
    fun assumesHttpsWhenSchemeIsOmitted() = runTest {
        val lookup = locate("  example.com/feed ", mapOf("/feed" to feed))

        assertEquals(FeedLookup.Found("https://example.com/feed", "Example Feed"), lookup)
    }

    /** ページがフィードを 1 つだけ案内していれば、相対 URL を解決してそのフィードを返す。 */
    @Test
    fun followsSingleFeedLinkOfPage() = runTest {
        val lookup = locate("https://example.com/blog/", mapOf("/blog/" to page(rssLink("../index.xml")), "/index.xml" to feed))

        assertEquals(FeedLookup.Found("https://example.com/index.xml", "Example Feed"), lookup)
    }

    /** ページが複数のフィードを案内していれば、候補として全て返す。フィード以外の案内は含めない。 */
    @Test
    fun returnsCandidatesWhenPageOffersSeveralFeeds() = runTest {
        val html = page(
            rssLink("/rss.xml", title = "RSS"),
            "<link rel=\"alternate\" type=\"application/atom+xml\" href=\"/atom.xml\">",
            "<link rel=\"alternate\" hreflang=\"en\" href=\"/en/\">",
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"/style.css\">",
        )

        val lookup = locate("https://example.com/", mapOf("/" to html))

        assertEquals(
            FeedLookup.Candidates(
                listOf(
                    FeedCandidate("https://example.com/rss.xml", "RSS"),
                    FeedCandidate("https://example.com/atom.xml", null),
                ),
            ),
            lookup,
        )
    }

    /** フィードでも、フィードを案内するページでもなければ失敗になる。取得できない場合も失敗になる。 */
    @Test
    fun failsWhenNoFeedIsFound() = runTest {
        val plainPage = locate("https://example.com/", mapOf("/" to page()))
        val brokenLink = locate("https://example.com/", mapOf("/" to page(rssLink("/missing.xml"))))
        val unreachable = locate("https://example.com/", emptyMap())

        assertIs<FeedLookup.Failed>(plainPage)
        assertIs<FeedLookup.Failed>(brokenLink)
        assertIs<FeedLookup.Failed>(unreachable)
    }
}
