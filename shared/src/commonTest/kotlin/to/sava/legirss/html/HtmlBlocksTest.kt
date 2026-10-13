package to.sava.legirss.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HtmlBlocksTest {
    private val base = "https://example.com/blog/post"

    private fun parse(source: String) = HtmlBlocks.parse(source, base)

    private fun paragraph(text: String, vararg spans: InlineSpan) = HtmlBlock.Paragraph(InlineText(text, spans.toList()))

    /** タグを含まない文字列は平文として、空でない行ごとに段落にする。 */
    @Test
    fun splitsPlainTextIntoParagraphsByLine() {
        assertEquals(listOf(paragraph("first line"), paragraph("second line")), parse("first line\n\n  second line  "))
    }

    /** 段落ごとにブロックを分け、連続する空白や改行は 1 つの空白にまとめる。 */
    @Test
    fun splitsParagraphsAndCollapsesWhitespace() {
        assertEquals(
            listOf(paragraph("Hello world"), paragraph("Second")),
            parse("<p>Hello\n   world</p>\n<p>  Second </p>"),
        )
    }

    /** br は段落の中の改行になる。 */
    @Test
    fun keepsLineBreaks() {
        assertEquals(listOf(paragraph("one\ntwo")), parse("<p>one<br>two</p>"))
    }

    /** 太字・斜体・コード・取り消し線を、文字の範囲つきの装飾として取り出す。 */
    @Test
    fun extractsInlineStyles() {
        val blocks = parse("<p>a <b>bold</b> <em>it</em> <code>c</code> <del>x</del></p>")

        assertEquals(
            listOf(
                paragraph(
                    "a bold it c x",
                    InlineSpan(2, 6, style = InlineStyle.BOLD),
                    InlineSpan(7, 9, style = InlineStyle.ITALIC),
                    InlineSpan(10, 11, style = InlineStyle.CODE),
                    InlineSpan(12, 13, style = InlineStyle.STRIKETHROUGH),
                ),
            ),
            blocks,
        )
    }

    /** リンクを文字の範囲つきで取り出し、相対 URL は基準の URL から解決する。 */
    @Test
    fun extractsLinksAndResolvesRelativeUrls() {
        val blocks = parse("<p>see <a href=\"/about\">about</a> and <a href=\"https://other.example/\">other</a></p>")

        assertEquals(
            listOf(
                paragraph(
                    "see about and other",
                    InlineSpan(4, 9, link = "https://example.com/about"),
                    InlineSpan(14, 19, link = "https://other.example/"),
                ),
            ),
            blocks,
        )
    }

    /** 画像は独立したブロックになり、前後の文は別の段落に分かれる。リンクの中の画像はリンク先を持つ。 */
    @Test
    fun emitsImagesAsBlocks() {
        val blocks = parse("<p>before <a href=\"/full\"><img src=\"thumb.png\" alt=\"Thumb\"></a> after</p>")

        assertEquals(
            listOf(
                paragraph("before"),
                HtmlBlock.Image("https://example.com/blog/thumb.png", "Thumb", "https://example.com/full"),
                paragraph("after"),
            ),
            blocks,
        )
    }

    /** 見出しは階層つきで、引用は中のブロックを入れ子にして取り出す。 */
    @Test
    fun readsHeadingsAndQuotes() {
        val blocks = parse("<h2>Title</h2><blockquote><p>quoted</p><p>more</p></blockquote>")

        assertEquals(
            listOf(
                HtmlBlock.Heading(2, InlineText("Title")),
                HtmlBlock.Quote(listOf(paragraph("quoted"), paragraph("more"))),
            ),
            blocks,
        )
    }

    /** 箇条書きは項目ごとのブロックになり、番号つきなら番号を、そうでなければ記号を付ける。入れ子も保つ。 */
    @Test
    fun readsLists() {
        val blocks = parse("<ol><li>one</li><li>two<ul><li>inner</li></ul></li></ol>")

        assertEquals(
            listOf(
                HtmlBlock.ListItem("1.", listOf(paragraph("one"))),
                HtmlBlock.ListItem("2.", listOf(paragraph("two"), HtmlBlock.ListItem("•", listOf(paragraph("inner"))))),
            ),
            blocks,
        )
    }

    /** 整形済みテキストは改行と字下げを保ち、水平線は区切りのブロックになる。 */
    @Test
    fun readsPreformattedAndRule() {
        val blocks = parse("<pre>line 1\n  line 2</pre><hr>")

        assertEquals(listOf(HtmlBlock.Preformatted("line 1\n  line 2"), HtmlBlock.Rule), blocks)
    }

    /** 表は行ごとに 1 段落にして、セルを区切り記号でつなぐ。 */
    @Test
    fun flattensTables() {
        val blocks = parse("<table><tr><th>Name</th><th>Size</th></tr><tr><td>a</td><td>1</td></tr></table>")

        assertEquals(listOf(paragraph("Name | Size"), paragraph("a | 1")), blocks)
    }

    /** スクリプト・スタイル・埋め込みは中身ごと捨てる。 */
    @Test
    fun dropsScriptsStylesAndEmbeds() {
        val blocks = parse(
            "<p>kept</p><script>alert(1)</script><style>p{}</style><iframe src=\"https://x.example/\">inner</iframe>",
        )

        assertEquals(listOf(paragraph("kept")), blocks)
    }

    /** 装飾の途中で画像が挟まっても、後ろの段落の装飾は文字の範囲に収まる。 */
    @Test
    fun keepsSpansWithinParagraphWhenBlockInterrupts() {
        val blocks = parse("<p>lead text <b>bold <img src=\"a.png\"> tail</b></p>")

        val last = assertIs<HtmlBlock.Paragraph>(blocks.last())
        assertEquals("tail", last.text.text)
        assertTrue(last.text.spans.all { it.start >= 0 && it.end <= last.text.text.length })
    }
}
