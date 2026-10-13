package to.sava.legirss.feed

import io.ktor.client.engine.cio.CIO

object FeedHttp {
    const val DEFAULT_TIMEOUT_MILLIS = 20_000L

    /** デスクトップで使う、フィードの取得口を作る。 */
    fun createFetcher(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): FeedFetcher =
        FeedFetcher(FeedFetcher.createClient(CIO.create(), timeoutMillis))
}
