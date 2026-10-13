package to.sava.legirss.feed

import io.ktor.client.engine.java.Java

object FeedHttp {
    const val DEFAULT_TIMEOUT_MILLIS = 20_000L

    /**
     * デスクトップで使う、フィードの取得口を作る。
     *
     * サーバごとに違う TLS の方式に広く対応できるよう、JDK の HTTP クライアントを使う。
     */
    fun createFetcher(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): FeedFetcher =
        FeedFetcher(FeedFetcher.createClient(Java.create(), timeoutMillis))
}
