package to.sava.legirss.feed

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

/** RSS 0.9x / 1.0 / 2.0 と Atom 0.3 / 1.0 を読む。 */
object FeedParser {
    private const val SUMMARY_TITLE_LENGTH = 70
    private const val ATOM_TYPE_XHTML = "xhtml"
    private val atomXhtmlTypes = setOf(ATOM_TYPE_XHTML, "application/xhtml+xml")
    private val whitespace = Regex("""\s+""")
    private val htmlTag = Regex("""<[^>]*>""")

    /**
     * [text] をフィードとして読む。フィードでなければ null。
     *
     * @param feedUrl フィードを取得した URL。相対 URL を解決する基準にする。
     */
    fun parse(text: String, feedUrl: String): ParsedFeed? {
        val root = Parser.xmlParser().parseInput(text, feedUrl).children().firstOrNull() ?: return null
        return when (root.tagName().lowercase()) {
            "rss" -> root.child("channel")?.let { channel -> parseRss(channel, channel.children("item"), feedUrl) }
            "rdf:rdf" -> root.child("channel")?.let { channel -> parseRss(channel, root.children("item"), feedUrl) }
            "feed" -> parseAtom(root, feedUrl)
            else -> null
        }
    }

    private fun parseRss(channel: Element, items: List<Element>, feedUrl: String): ParsedFeed {
        val siteUrl = channel.childText("link")?.let { resolveUrl(feedUrl, it) }
        return ParsedFeed(
            title = channel.childText("title")?.singleLine(),
            siteUrl = siteUrl,
            items = items.mapNotNull { parseRssItem(it, base = siteUrl ?: feedUrl) },
        )
    }

    private fun parseRssItem(item: Element, base: String): ParsedItem? {
        val guid = item.childText("guid")
        val summary = item.childBody("description") ?: item.childBody("dc:description")
        val link = item.childText("link") ?: guid?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return ParsedItem(
            guid = guid,
            title = titleOf(item.childText("title"), summary) ?: return null,
            link = link?.let { resolveUrl(base, it) },
            summary = summary,
            content = item.childBody("content:encoded"),
            author = (item.childText("dc:creator") ?: item.childText("author"))?.singleLine(),
            category = (item.childText("category") ?: item.childText("dc:subject"))?.singleLine(),
            publishedAt = listOf("dc:date", "pubDate").firstNotNullOfOrNull { name -> item.childText(name)?.let(FeedDates::parse) },
        )
    }

    private fun parseAtom(feed: Element, feedUrl: String): ParsedFeed {
        val feedBase = baseOf(feed, feedUrl)
        return ParsedFeed(
            title = feed.child("title")?.atomText()?.singleLine(),
            siteUrl = alternateLinkOf(feed)?.let { resolveUrl(feedBase, it) },
            items = feed.children("entry").mapNotNull { parseAtomEntry(it, feedBase) },
        )
    }

    private fun parseAtomEntry(entry: Element, feedBase: String): ParsedItem? {
        val summary = entry.child("summary")?.atomText()
        return ParsedItem(
            guid = entry.childText("id"),
            title = titleOf(entry.child("title")?.atomText(), summary) ?: return null,
            link = alternateLinkOf(entry)?.let { resolveUrl(baseOf(entry, feedBase), it) },
            summary = summary,
            content = entry.child("content")?.atomText(),
            author = (entry.child("author")?.childText("name") ?: entry.childText("dc:creator"))?.singleLine(),
            category = entry.child("category")?.let { it.attributeText("term") ?: it.text().trim().ifEmpty { null } },
            publishedAt = listOf("published", "created", "issued", "updated", "modified")
                .firstNotNullOfOrNull { name -> entry.childText(name)?.let(FeedDates::parse) },
        )
    }

    /** `rel` が `alternate` か、`rel` の無い `link` の `href`。 */
    private fun alternateLinkOf(element: Element): String? =
        element.children("link")
            .firstOrNull { link -> (link.attributeText("rel") ?: "alternate") == "alternate" }
            ?.attributeText("href")

    private fun baseOf(element: Element, parentBase: String): String =
        element.attributeText("xml:base")?.let { resolveUrl(parentBase, it) } ?: parentBase

    /** Atom のテキスト要素の中身。`type` が xhtml なら包んでいる div の中のマークアップを返す。 */
    private fun Element.atomText(): String? {
        val value = if (attributeText("type")?.lowercase() in atomXhtmlTypes) (child("div") ?: this).html() else wholeText()
        return value.trim().ifEmpty { null }
    }

    /** タイトルが空なら、要約からタグを除いた先頭部分で代える。それも無ければ null。 */
    private fun titleOf(title: String?, summary: String?): String? =
        title?.singleLine()?.ifEmpty { null }
            ?: summary?.replace(htmlTag, " ")?.singleLine()?.take(SUMMARY_TITLE_LENGTH)?.ifEmpty { null }

    private fun String.singleLine(): String = replace(whitespace, " ").trim()

    private fun Element.child(name: String): Element? = children().firstOrNull { it.tagName().equals(name, ignoreCase = true) }

    private fun Element.children(name: String): List<Element> = children().filter { it.tagName().equals(name, ignoreCase = true) }

    private fun Element.childText(name: String): String? = child(name)?.text()?.trim()?.ifEmpty { null }

    /** 要約や本文として使う中身。改行を保ったまま取り出す。 */
    private fun Element.childBody(name: String): String? = child(name)?.wholeText()?.trim()?.ifEmpty { null }

    private fun Element.attributeText(name: String): String? = attr(name).trim().ifEmpty { null }
}
