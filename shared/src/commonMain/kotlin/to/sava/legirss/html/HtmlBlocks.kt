package to.sava.legirss.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import to.sava.legirss.feed.resolveUrl

/** 記事の要約・本文（HTML または平文）を、描画用のブロックの並びに変える。 */
object HtmlBlocks {
    private const val BULLET = "•"
    private const val TABLE_CELL_SEPARATOR = " | "

    private val droppedTags = setOf(
        "script", "style", "iframe", "object", "embed", "noscript", "svg", "form", "head", "template", "video", "audio",
    )
    private val headingTags = (1..6).associateBy { "h$it" }
    private val inlineStyles = mapOf(
        "b" to InlineStyle.BOLD, "strong" to InlineStyle.BOLD,
        "i" to InlineStyle.ITALIC, "em" to InlineStyle.ITALIC, "cite" to InlineStyle.ITALIC,
        "code" to InlineStyle.CODE, "tt" to InlineStyle.CODE, "kbd" to InlineStyle.CODE, "samp" to InlineStyle.CODE,
        "s" to InlineStyle.STRIKETHROUGH, "del" to InlineStyle.STRIKETHROUGH, "strike" to InlineStyle.STRIKETHROUGH,
    )
    private val blockTags = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "main", "nav", "figure", "figcaption",
        "center", "address", "details", "summary", "dl", "dt", "dd", "li", "tr", "td", "th",
    )
    private val whitespace = Regex("""\s+""")

    /**
     * @param baseUrl リンクと画像の相対 URL を解決する基準。
     */
    fun parse(source: String, baseUrl: String?): List<HtmlBlock> =
        if ('<' in source) {
            Builder(baseUrl).apply { walkChildren(Ksoup.parseBodyFragment(source).body(), link = null) }.finish()
        } else {
            // タグを含まない要約は平文として扱い、行ごとに段落にする。
            source.lines().map(String::trim).filter(String::isNotEmpty).map { HtmlBlock.Paragraph(InlineText(it)) }
        }

    private class Builder(private val baseUrl: String?) {
        private val blocks = mutableListOf<HtmlBlock>()
        private val text = StringBuilder()
        private val spans = mutableListOf<InlineSpan>()

        /** 段落を締めた回数。装飾の途中で段落が切り替わったことを知るために数える。 */
        private var paragraphCount = 0

        fun finish(): List<HtmlBlock> {
            flush()
            return blocks
        }

        fun walkChildren(parent: Node, link: String?) {
            parent.childNodes().forEach { walk(it, link) }
        }

        private fun walk(node: Node, link: String?) {
            when (node) {
                is TextNode -> appendText(node.getWholeText())
                is Element -> walkElement(node, link)
                else -> Unit
            }
        }

        private fun walkElement(element: Element, link: String?) {
            val tag = element.normalName()
            when {
                tag in droppedTags -> Unit
                tag == "br" -> text.append('\n')
                tag == "hr" -> addBlock(HtmlBlock.Rule)
                tag == "img" -> element.attr("src").trim().takeIf(String::isNotEmpty)?.let { source ->
                    addBlock(HtmlBlock.Image(resolveUrl(baseUrl, source), element.attr("alt").trim().ifEmpty { null }, link))
                }
                tag == "pre" -> element.wholeText().trim('\n', '\r').takeIf(String::isNotBlank)?.let { addBlock(HtmlBlock.Preformatted(it)) }
                tag in headingTags -> {
                    flush()
                    walkChildren(element, link)
                    flush { HtmlBlock.Heading(headingTags.getValue(tag), it) }
                }
                tag == "blockquote" -> nested(element, link).takeIf { it.isNotEmpty() }?.let { addBlock(HtmlBlock.Quote(it)) }
                tag == "ul" || tag == "ol" -> addListItems(element, ordered = tag == "ol", link)
                tag == "table" -> addTableRows(element)
                tag in blockTags -> {
                    flush()
                    walkChildren(element, link)
                    flush()
                }
                tag == "a" -> (hrefOf(element) ?: link).let { target ->
                    withSpan({ start, end -> InlineSpan(start, end, link = target) }) { walkChildren(element, target) }
                }
                tag in inlineStyles -> withSpan({ start, end -> InlineSpan(start, end, style = inlineStyles.getValue(tag)) }) {
                    walkChildren(element, link)
                }
                else -> walkChildren(element, link)
            }
        }

        private fun hrefOf(element: Element): String? =
            element.attr("href").trim().takeIf(String::isNotEmpty)?.let { resolveUrl(baseUrl, it) }

        private fun addListItems(list: Element, ordered: Boolean, link: String?) {
            flush()
            list.children().filter { it.normalName() == "li" }.forEachIndexed { index, item ->
                nested(item, link).takeIf { it.isNotEmpty() }?.let {
                    blocks.add(HtmlBlock.ListItem(if (ordered) "${index + 1}." else BULLET, it))
                }
            }
        }

        /** 表は行ごとに 1 段落とし、セルを区切り記号でつなぐ。 */
        private fun addTableRows(table: Element) {
            flush()
            table.select("tr").forEach { row ->
                row.children().map { it.text().trim() }.filter(String::isNotEmpty).joinToString(TABLE_CELL_SEPARATOR)
                    .takeIf(String::isNotEmpty)
                    ?.let { blocks.add(HtmlBlock.Paragraph(InlineText(it))) }
            }
        }

        private fun nested(element: Element, link: String?): List<HtmlBlock> =
            Builder(baseUrl).apply { walkChildren(element, link) }.finish()

        private fun addBlock(block: HtmlBlock) {
            flush()
            blocks.add(block)
        }

        /** 連続する空白を 1 つにまとめて足す。行頭の空白は足さない。 */
        private fun appendText(raw: String) {
            val collapsed = raw.replace(whitespace, " ")
            val atLineStart = text.isEmpty() || text.last() == '\n' || text.last() == ' '
            text.append(if (atLineStart) collapsed.trimStart() else collapsed)
        }

        private fun withSpan(span: (start: Int, end: Int) -> InlineSpan, content: () -> Unit) {
            val paragraphAtStart = paragraphCount
            val startInParagraph = text.length
            content()
            // 途中で段落が切り替わった場合は、最後の段落の先頭からを装飾の範囲にする。
            val start = if (paragraphCount == paragraphAtStart) startInParagraph else 0
            if (text.length > start) spans.add(span(start, text.length))
        }

        private fun flush(toBlock: (InlineText) -> HtmlBlock = { HtmlBlock.Paragraph(it) }) {
            val trimmed = text.toString().trimEnd()
            if (trimmed.isNotBlank()) {
                val clamped = spans
                    .map { it.copy(end = it.end.coerceAtMost(trimmed.length)) }
                    .filter { it.start < it.end && (it.style != null || it.link != null) }
                blocks.add(toBlock(InlineText(trimmed, clamped)))
            }
            text.clear()
            spans.clear()
            paragraphCount++
        }
    }
}
