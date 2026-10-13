package to.sava.legirss.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import to.sava.legirss.AppLog
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** 配布元から、版の知らせと配布物を取得する。 */
internal class UpdateHttp(private val ioDispatcher: CoroutineDispatcher) {
    // 配布物の URL は保管場所へ転送されるので、転送をたどる。
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    /** [url] の中身を返す。取得できなければ、理由を記録して null を返す。 */
    suspend fun fetch(url: String): ByteArray? =
        try {
            send(url, HttpResponse.BodyHandlers.ofByteArray())
        } catch (exception: IOException) {
            AppLog.warn("取得できません: $url", exception)
            null
        }

    /**
     * [url] の中身を [target] に保存する。
     *
     * @throws IOException 取得できない。
     */
    suspend fun download(url: String, target: Path) {
        send(url, HttpResponse.BodyHandlers.ofFile(target))
    }

    private suspend fun <T> send(url: String, handler: HttpResponse.BodyHandler<T>): T = runInterruptible(ioDispatcher) {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)).GET().build()
        val response = client.send(request, handler)
        if (response.statusCode() !in SUCCESS_STATUS_RANGE) throw IOException("HTTP ${response.statusCode()}")
        response.body()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val REQUEST_TIMEOUT_SECONDS = 300L
        val SUCCESS_STATUS_RANGE = 200..299
    }
}
