package to.sava.legirss.feed

import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** ページが案内しているフィード。 */
data class FeedCandidate(
    val url: String,
    val title: String?,
)

/** 入力された URL からフィードを探した結果。 */
sealed interface FeedLookup {
    /** フィードが 1 つに決まった。 */
    data class Found(
        val feedUrl: String,
        /** フィードが名乗るタイトル。 */
        val title: String?,
    ) : FeedLookup

    /** ページが複数のフィードを案内している。 */
    data class Candidates(val candidates: List<FeedCandidate>) : FeedLookup

    data class Failed(val reason: String) : FeedLookup
}

/** HTML のページが `<link rel="alternate">` で案内しているフィードを拾う。 */
object FeedLinks {
    private val feedTypes = setOf("application/rss+xml", "application/atom+xml")

    fun find(html: String, pageUrl: String): List<FeedCandidate> =
        Ksoup.parse(html).select("link[href]")
            .filter { link ->
                link.attr("rel").lowercase().split(' ').contains("alternate") && link.attr("type").trim().lowercase() in feedTypes
            }
            .map { link -> FeedCandidate(resolveUrl(pageUrl, link.attr("href").trim()), link.attr("title").trim().ifEmpty { null }) }
            .distinctBy { it.url }
}

/** 利用者が入力した URL から、登録するフィードを探す。 */
class FeedLocator(
    private val fetcher: FeedFetcher,
    /** 取得した内容の解釈に使う。 */
    private val parseDispatcher: CoroutineDispatcher,
) {
    /**
     * [input] がフィードならそれを、HTML のページならそこで案内されているフィードを返す。
     *
     * 案内が 1 つだけなら、その先がフィードであることまで確かめる。
     */
    suspend fun locate(input: String): FeedLookup {
        val url = withScheme(input.trim())
        return when (val content = load(url)) {
            is Content.Failed -> FeedLookup.Failed(content.reason)
            is Content.Feed -> FeedLookup.Found(content.url, content.title)
            is Content.Page -> when (content.candidates.size) {
                0 -> FeedLookup.Failed(NOT_FOUND_MESSAGE)
                1 -> locateFeed(content.candidates.single().url)
                else -> FeedLookup.Candidates(content.candidates)
            }
        }
    }

    private suspend fun locateFeed(url: String): FeedLookup = when (val content = load(url)) {
        is Content.Failed -> FeedLookup.Failed(content.reason)
        is Content.Feed -> FeedLookup.Found(content.url, content.title)
        is Content.Page -> FeedLookup.Failed(NOT_FOUND_MESSAGE)
    }

    private suspend fun load(url: String): Content = when (val result = fetcher.fetch(FetchRequest(url, etag = null, lastModified = null))) {
        is FetchResult.Failed -> Content.Failed(result.detail)
        is FetchResult.NotModified -> Content.Failed(NOT_FOUND_MESSAGE)
        is FetchResult.Fetched -> withContext(parseDispatcher) {
            val finalUrl = result.permanentUrl ?: url
            val text = FeedText.decode(result.body, result.charset)
            FeedParser.parse(text, finalUrl)?.let { Content.Feed(finalUrl, it.title) } ?: Content.Page(FeedLinks.find(text, finalUrl))
        }
    }

    private sealed interface Content {
        data class Feed(val url: String, val title: String?) : Content

        data class Page(val candidates: List<FeedCandidate>) : Content

        data class Failed(val reason: String) : Content
    }

    private companion object {
        const val NOT_FOUND_MESSAGE = "フィードが見つかりません"
        const val SCHEME_SEPARATOR = "://"
        const val DEFAULT_SCHEME = "https"

        fun withScheme(url: String): String = if (SCHEME_SEPARATOR in url) url else "$DEFAULT_SCHEME$SCHEME_SEPARATOR$url"
    }
}
