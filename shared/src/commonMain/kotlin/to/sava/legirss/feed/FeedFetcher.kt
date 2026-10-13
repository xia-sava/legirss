package to.sava.legirss.feed

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLParserException
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.charsets.name
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

data class FetchRequest(
    val url: String,
    /** 前回の応答の ETag。 */
    val etag: String?,
    /** 前回の応答の Last-Modified。 */
    val lastModified: String?,
)

enum class FetchFailure {
    /** 接続できない、応答が時間内に返らない、など。 */
    NO_RESPONSE,

    /** 成功以外のステータスが返った。 */
    HTTP_ERROR,
}

sealed interface FetchResult {
    /** 恒久的な転送（301 / 308）だけをたどって着いた URL。転送が無いか、一時的な転送を含めば null。 */
    val permanentUrl: String?

    data class Fetched(
        val body: ByteArray,
        /** Content-Type ヘッダに書かれていた文字コード。 */
        val charset: String?,
        val etag: String?,
        val lastModified: String?,
        override val permanentUrl: String?,
    ) : FetchResult

    data class NotModified(override val permanentUrl: String?) : FetchResult

    data class Failed(
        val kind: FetchFailure,
        val detail: String,
        /** フィードが無くなったことを示すステータス（404 / 410）だった。 */
        val gone: Boolean,
    ) : FetchResult {
        override val permanentUrl: String? get() = null
    }
}

/** フィードを HTTP で取得する。 */
class FeedFetcher(private val client: HttpClient) {
    suspend fun fetch(request: FetchRequest): FetchResult =
        try {
            follow(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: IOException) {
            noResponse(exception)
        } catch (exception: UnresolvedAddressException) {
            noResponse(exception)
        } catch (exception: URLParserException) {
            noResponse(exception)
        }

    private suspend fun follow(request: FetchRequest): FetchResult {
        var url = request.url
        var permanentUrl: String? = null
        var permanentSoFar = true
        repeat(MAX_REDIRECTS + 1) { hop ->
            val response = client.get(url) {
                // 条件付き取得のヘッダは、保存した値を得た URL（転送前の URL）にだけ付ける。
                if (hop == 0) {
                    request.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                    request.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
                }
            }
            val location = response.headers[HttpHeaders.Location]?.takeIf { response.status in redirectStatuses }
                ?: return toResult(response, permanentUrl)
            url = resolveUrl(url, location)
            permanentSoFar = permanentSoFar && response.status in permanentRedirectStatuses
            permanentUrl = url.takeIf { permanentSoFar }
        }
        return FetchResult.Failed(FetchFailure.HTTP_ERROR, "転送が $MAX_REDIRECTS 回を超えました", gone = false)
    }

    private suspend fun toResult(response: HttpResponse, permanentUrl: String?): FetchResult = when {
        response.status == HttpStatusCode.NotModified -> FetchResult.NotModified(permanentUrl)
        response.status.value in SUCCESS_STATUS_RANGE -> FetchResult.Fetched(
            body = response.bodyAsBytes(),
            charset = response.contentType()?.charset()?.name,
            etag = response.headers[HttpHeaders.ETag],
            lastModified = response.headers[HttpHeaders.LastModified],
            permanentUrl = permanentUrl,
        )
        else -> FetchResult.Failed(
            kind = FetchFailure.HTTP_ERROR,
            detail = "HTTP ${response.status.value} ${response.status.description}".trim(),
            gone = response.status in goneStatuses,
        )
    }

    private fun noResponse(exception: Exception): FetchResult.Failed =
        FetchResult.Failed(FetchFailure.NO_RESPONSE, exception.message ?: exception::class.simpleName.orEmpty(), gone = false)

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "legirss"
        private val SUCCESS_STATUS_RANGE = 200..299
        private val permanentRedirectStatuses = setOf(HttpStatusCode.MovedPermanently, HttpStatusCode.PermanentRedirect)
        private val redirectStatuses = permanentRedirectStatuses +
            setOf(HttpStatusCode.Found, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect)
        private val goneStatuses = setOf(HttpStatusCode.NotFound, HttpStatusCode.Gone)

        /** 取得用の HTTP クライアントを作る。転送はどの種類だったかを見るため、自前でたどる。 */
        fun createClient(engine: HttpClientEngine, timeoutMillis: Long): HttpClient = HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
            install(UserAgent) { agent = USER_AGENT }
            install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
        }
    }
}
