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
        URLBuilder(base).takeFrom(reference).apply { encodedPathSegments = withoutDotSegments(encodedPathSegments) }.buildString()
    } catch (_: URLParserException) {
        reference
    } catch (_: IllegalArgumentException) {
        reference
    }
}

private const val CURRENT_SEGMENT = "."
private const val PARENT_SEGMENT = ".."

/** パスの区切りごとの並びから `.` と `..` を解決して取り除く。先頭の空の要素はルートを表すので残す。 */
private fun withoutDotSegments(segments: List<String>): List<String> {
    val resolved = segments.fold(emptyList<String>()) { kept, segment ->
        when (segment) {
            CURRENT_SEGMENT -> kept
            PARENT_SEGMENT -> if (kept.lastOrNull().isNullOrEmpty()) kept else kept.dropLast(1)
            else -> kept + segment
        }
    }
    // `.` や `..` で終わるパスはディレクトリを指すので、末尾の区切りを保つ。
    val endsWithDirectory = segments.lastOrNull() in setOf(CURRENT_SEGMENT, PARENT_SEGMENT)
    return if (endsWithDirectory && resolved.lastOrNull() != "") resolved + "" else resolved
}
