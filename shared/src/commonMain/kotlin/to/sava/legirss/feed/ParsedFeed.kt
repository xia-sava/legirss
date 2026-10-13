package to.sava.legirss.feed

import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.takeFrom

data class ParsedFeed(
    val title: String?,
    val siteUrl: String?,
    val items: List<ParsedItem>,
)

data class ParsedItem(
    /** RSS の `guid`、Atom の `id`。 */
    val guid: String?,
    val title: String,
    val link: String?,
    val summary: String?,
    val content: String?,
    val author: String?,
    val category: String?,
    val publishedAt: Long?,
) {
    /** 同じ記事かどうかを判定する鍵。`guid`、リンク、タイトルの順で最初にあるものを使う。 */
    val identity: String get() = guid ?: link ?: title
}

/** [reference] を [base] からの相対 URL として解決する。解決できなければ [reference] をそのまま返す。 */
fun resolveUrl(base: String?, reference: String): String {
    if (base.isNullOrBlank()) return reference
    return try {
        URLBuilder(base).takeFrom(reference).buildString()
    } catch (_: URLParserException) {
        reference
    } catch (_: IllegalArgumentException) {
        reference
    }
}
