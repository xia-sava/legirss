package to.sava.legirss.html

enum class InlineStyle {
    BOLD,
    ITALIC,
    CODE,
    STRIKETHROUGH,
}

/** 文字列の一部に掛かる装飾。[style] と [link] のどちらか一方を持つ。 */
data class InlineSpan(
    val start: Int,
    val end: Int,
    val style: InlineStyle? = null,
    val link: String? = null,
)

/** 装飾とリンクを持つ 1 段落ぶんの文字列。 */
data class InlineText(
    val text: String,
    val spans: List<InlineSpan> = emptyList(),
)

/** 記事の本文を描画するための、ブロック単位の構造。 */
sealed interface HtmlBlock {
    data class Paragraph(val text: InlineText) : HtmlBlock

    data class Heading(val level: Int, val text: InlineText) : HtmlBlock

    /** [link] は画像がリンクの中にあった場合のリンク先。 */
    data class Image(val url: String, val alt: String?, val link: String?) : HtmlBlock

    data class Quote(val blocks: List<HtmlBlock>) : HtmlBlock

    /** 箇条書きの 1 項目。[marker] は先頭に出す記号や番号。 */
    data class ListItem(val marker: String, val blocks: List<HtmlBlock>) : HtmlBlock

    data class Preformatted(val text: String) : HtmlBlock

    data object Rule : HtmlBlock
}
