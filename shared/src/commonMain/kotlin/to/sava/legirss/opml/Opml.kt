package to.sava.legirss.opml

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.NewGroup
import to.sava.legirss.model.NewNode
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode

/** 購読しているフィードの一覧を、OPML の形式で読み書きする。 */
object Opml {
    private const val OUTLINE = "outline"
    private const val INDENT = "  "

    /**
     * [text] を OPML として読み、登録するノードの木にする。OPML でなければ null。
     *
     * フィードの URL を持つ項目をチャンネル、持たない項目をグループとして扱う。名前の無い項目は URL を名前にする。
     */
    fun parse(text: String): List<NewNode>? {
        val root = Parser.xmlParser().parseInput(text, "").children().firstOrNull { it.tagName().equals("opml", ignoreCase = true) }
            ?: return null
        val body = root.children().firstOrNull { it.tagName().equals("body", ignoreCase = true) } ?: return null
        return outlinesOf(body).mapNotNull(::toNode)
    }

    private fun outlinesOf(element: Element): List<Element> = element.children().filter { it.tagName().equals(OUTLINE, ignoreCase = true) }

    private fun toNode(outline: Element): NewNode? {
        val name = outline.attributeText("text") ?: outline.attributeText("title")
        val feedUrl = outline.attributeText("xmlUrl")
            ?: return name?.let { NewGroup(it, outlinesOf(outline).mapNotNull(::toNode)) }
        return NewChannel(
            name = name ?: feedUrl,
            feedUrl = feedUrl,
            siteUrl = outline.attributeText("htmlUrl"),
            title = null,
            paused = false,
            firstFetchAsRead = false,
            articles = emptyList(),
        )
    }

    /**
     * [nodes] から、[knownFeedUrls] にあるフィードのチャンネルと、同じフィードの 2 つ目以降のチャンネルを除く。
     *
     * 除いた結果、チャンネルが 1 つも残らないグループも除く。
     */
    fun withoutKnownFeeds(nodes: List<NewNode>, knownFeedUrls: Set<String>): List<NewNode> {
        val seen = knownFeedUrls.toMutableSet()

        fun keep(node: NewNode): NewNode? = when (node) {
            is NewChannel -> node.takeIf { seen.add(it.feedUrl) }
            is NewGroup -> node.children.mapNotNull(::keep).takeIf { it.isNotEmpty() }?.let { node.copy(children = it) }
        }
        return nodes.mapNotNull(::keep)
    }

    fun countChannels(nodes: List<NewNode>): Int = nodes.sumOf { node ->
        when (node) {
            is NewChannel -> 1
            is NewGroup -> countChannels(node.children)
        }
    }

    /** 属性の値を、名前の大文字と小文字を区別せずに引く。無いか、空白だけなら null。 */
    private fun Element.attributeText(name: String): String? =
        attributes().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.ifEmpty { null }

    /**
     * [nodes] のうち [parentId] の配下にあるものを、OPML として書き出す。[parentId] が null なら全体を書き出す。
     *
     * @param title 文書の題。
     */
    fun write(nodes: List<TreeNode>, parentId: Long?, title: String): String {
        val childrenByParent = nodes.groupBy { it.parentId }.mapValues { (_, children) -> children.sortedBy { it.position } }

        fun StringBuilder.appendOutline(node: TreeNode, depth: Int) {
            val indent = INDENT.repeat(depth)
            val name = escape(node.name)
            when (node.kind) {
                NodeKind.CHANNEL -> {
                    append("$indent<outline type=\"rss\" text=\"$name\" title=\"$name\" xmlUrl=\"${escape(node.channel?.feedUrl.orEmpty())}\"")
                    node.channel?.siteUrl?.let { append(" htmlUrl=\"${escape(it)}\"") }
                    appendLine("/>")
                }

                NodeKind.GROUP -> {
                    appendLine("$indent<outline text=\"$name\" title=\"$name\">")
                    childrenByParent[node.id].orEmpty().forEach { appendOutline(it, depth + 1) }
                    appendLine("$indent</outline>")
                }
            }
        }

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<opml version=\"2.0\">")
            appendLine("$INDENT<head>")
            appendLine("$INDENT$INDENT<title>${escape(title)}</title>")
            appendLine("$INDENT</head>")
            appendLine("$INDENT<body>")
            childrenByParent[parentId].orEmpty().forEach { appendOutline(it, depth = 2) }
            appendLine("$INDENT</body>")
            appendLine("</opml>")
        }
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
