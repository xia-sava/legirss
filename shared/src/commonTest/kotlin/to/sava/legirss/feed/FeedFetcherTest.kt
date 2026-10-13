package to.sava.legirss.feed

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedFetcherTest {
    private val url = "https://example.com/feed"
    private val requests = mutableListOf<HttpRequestData>()

    private fun fetcher(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FeedFetcher {
        val engine = MockEngine { request ->
            requests.add(request)
            handler(request)
        }
        return FeedFetcher(FeedFetcher.createClient(engine, timeoutMillis = 1_000))
    }

    private fun MockRequestHandleScope.redirect(status: HttpStatusCode, location: String) =
        respond("", status, headersOf(HttpHeaders.Location, location))

    /** 成功した応答から、本文・文字コード・ETag・Last-Modified を取り出す。 */
    @Test
    fun returnsBodyAndValidators() = runTest {
        val fetcher = fetcher {
            respond(
                content = "<rss/>",
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/xml; charset=Shift_JIS"),
                    HttpHeaders.ETag to listOf("\"v1\""),
                    HttpHeaders.LastModified to listOf("Fri, 14 Aug 2026 09:30:29 GMT"),
                ),
            )
        }

        val result = assertIs<FetchResult.Fetched>(fetcher.fetch(FetchRequest(url, etag = null, lastModified = null)))

        assertContentEquals("<rss/>".encodeToByteArray(), result.body)
        assertEquals("shift_jis", result.charset?.lowercase())
        assertEquals("\"v1\"", result.etag)
        assertEquals("Fri, 14 Aug 2026 09:30:29 GMT", result.lastModified)
        assertNull(result.permanentUrl)
    }

    /** 保存してあった ETag と Last-Modified を条件付き取得のヘッダで送り、304 を「変更なし」として返す。 */
    @Test
    fun sendsValidatorsAndReportsNotModified() = runTest {
        val fetcher = fetcher { respond("", HttpStatusCode.NotModified) }

        val result = fetcher.fetch(FetchRequest(url, etag = "\"v1\"", lastModified = "Fri, 14 Aug 2026 09:30:29 GMT"))

        assertIs<FetchResult.NotModified>(result)
        assertEquals("\"v1\"", requests.single().headers[HttpHeaders.IfNoneMatch])
        assertEquals("Fri, 14 Aug 2026 09:30:29 GMT", requests.single().headers[HttpHeaders.IfModifiedSince])
    }

    /** 恒久的な転送をたどり、着いた URL を報告する。転送先には条件付き取得のヘッダを送らない。 */
    @Test
    fun followsPermanentRedirect() = runTest {
        val fetcher = fetcher { request ->
            if (request.url.encodedPath == "/feed") redirect(HttpStatusCode.MovedPermanently, "/new/feed") else respond("<rss/>")
        }

        val result = assertIs<FetchResult.Fetched>(fetcher.fetch(FetchRequest(url, etag = "\"v1\"", lastModified = null)))

        assertEquals("https://example.com/new/feed", result.permanentUrl)
        assertEquals(listOf("/feed", "/new/feed"), requests.map { it.url.encodedPath })
        assertNull(requests[1].headers[HttpHeaders.IfNoneMatch])
    }

    /** 一時的な転送もたどるが、着いた URL を恒久的な移転先としては報告しない。 */
    @Test
    fun followsTemporaryRedirectWithoutReportingUrl() = runTest {
        val fetcher = fetcher { request ->
            if (request.url.host == "example.com") redirect(HttpStatusCode.Found, "https://cdn.example.com/feed") else respond("<rss/>")
        }

        val result = assertIs<FetchResult.Fetched>(fetcher.fetch(FetchRequest(url, etag = null, lastModified = null)))

        assertNull(result.permanentUrl)
        assertEquals("cdn.example.com", requests.last().url.host)
    }

    /** 転送が上限を超えて続いたら失敗にする。 */
    @Test
    fun givesUpOnRedirectLoop() = runTest {
        val fetcher = fetcher { redirect(HttpStatusCode.Found, "/feed") }

        val result = assertIs<FetchResult.Failed>(fetcher.fetch(FetchRequest(url, etag = null, lastModified = null)))

        assertEquals(FetchFailure.HTTP_ERROR, result.kind)
        assertEquals(6, requests.size)
    }

    /** 404 と 410 は「フィードが無くなった」失敗、それ以外のエラーステータスはただの失敗になる。 */
    @Test
    fun classifiesErrorStatuses() = runTest {
        val statuses = mapOf(
            HttpStatusCode.NotFound to true,
            HttpStatusCode.Gone to true,
            HttpStatusCode.InternalServerError to false,
            HttpStatusCode.Forbidden to false,
        )
        statuses.forEach { (status, gone) ->
            val result = assertIs<FetchResult.Failed>(
                fetcher { respondError(status) }.fetch(FetchRequest(url, etag = null, lastModified = null)),
            )

            assertEquals(FetchFailure.HTTP_ERROR, result.kind, status.toString())
            assertEquals(gone, result.gone, status.toString())
            assertTrue(status.value.toString() in result.detail, status.toString())
        }
    }

    /** 接続の失敗は例外にせず、「応答なし」の失敗として返す。 */
    @Test
    fun reportsConnectionFailure() = runTest {
        val fetcher = fetcher { throw IOException("connection refused") }

        val result = assertIs<FetchResult.Failed>(fetcher.fetch(FetchRequest(url, etag = null, lastModified = null)))

        assertEquals(FetchFailure.NO_RESPONSE, result.kind)
        assertEquals("connection refused", result.detail)
        assertFalse(result.gone)
    }
}
