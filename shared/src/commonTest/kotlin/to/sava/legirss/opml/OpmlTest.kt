package to.sava.legirss.opml

import to.sava.legirss.model.ChannelInfo
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.NewGroup
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpmlTest {
    private fun newChannel(name: String, feedUrl: String, siteUrl: String? = null) =
        NewChannel(name, feedUrl, siteUrl, title = null, paused = false, firstFetchAsRead = false, articles = emptyList())

    /** フィードの URL を持つ項目はチャンネルに、持たない項目は中の項目を抱えたグループになる。 */
    @Test
    fun readsGroupsAndChannels() {
        val nodes = Opml.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <head><title>feeds</title></head>
              <body>
                <outline text="News">
                  <outline type="rss" text="Alpha" xmlUrl="https://alpha.example/feed" htmlUrl="https://alpha.example/"/>
                  <outline text="Sub"><outline title="Beta" xmlUrl="https://beta.example/rss"/></outline>
                </outline>
                <outline xmlUrl="https://gamma.example/atom?a=1&amp;b=2"/>
              </body>
            </opml>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                NewGroup(
                    "News",
                    listOf(
                        newChannel("Alpha", "https://alpha.example/feed", "https://alpha.example/"),
                        NewGroup("Sub", listOf(newChannel("Beta", "https://beta.example/rss"))),
                    ),
                ),
                newChannel("https://gamma.example/atom?a=1&b=2", "https://gamma.example/atom?a=1&b=2"),
            ),
            nodes,
        )
    }

    /** OPML でない文書は読めないものとして扱う。 */
    @Test
    fun rejectsOtherDocuments() {
        assertNull(Opml.parse("<rss version=\"2.0\"><channel><title>Feed</title></channel></rss>"))
        assertNull(Opml.parse("not xml"))
    }

    /** 登録済みのフィードと、同じフィードの 2 つ目以降を除く。それでチャンネルが無くなったグループも除く。 */
    @Test
    fun dropsKnownAndRepeatedFeeds() {
        val nodes = listOf(
            NewGroup("Known only", listOf(newChannel("Alpha", "https://alpha.example/feed"))),
            NewGroup("Mixed", listOf(newChannel("Alpha again", "https://alpha.example/feed"), newChannel("Beta", "https://beta.example/rss"))),
            newChannel("Beta again", "https://beta.example/rss"),
            newChannel("Gamma", "https://gamma.example/atom"),
        )

        val kept = Opml.withoutKnownFeeds(nodes, knownFeedUrls = setOf("https://alpha.example/feed"))

        assertEquals(
            listOf(
                NewGroup("Mixed", listOf(newChannel("Beta", "https://beta.example/rss"))),
                newChannel("Gamma", "https://gamma.example/atom"),
            ),
            kept,
        )
        assertEquals(2, Opml.countChannels(kept))
    }

    private fun channel(id: Long, parentId: Long?, position: Long, name: String, feedUrl: String, siteUrl: String? = null) = TreeNode(
        id = id,
        parentId = parentId,
        position = position,
        kind = NodeKind.CHANNEL,
        name = name,
        channel = ChannelInfo(feedUrl, siteUrl, title = null, paused = false, hasError = false),
    )

    private val tree = listOf(
        channel(3, parentId = null, position = 1, name = "Q&A <\"daily\">", feedUrl = "https://qa.example/feed?a=1&b=2"),
        TreeNode(1, parentId = null, position = 0, kind = NodeKind.GROUP, name = "News", channel = null),
        channel(2, parentId = 1, position = 0, name = "Alpha", feedUrl = "https://alpha.example/feed", siteUrl = "https://alpha.example/"),
    )

    /** 書き出した OPML を読み戻すと、並び順・入れ子・名前と URL が元の木と同じになる。記号を含む名前と URL も保たれる。 */
    @Test
    fun writesTreeThatReadsBackTheSame() {
        val nodes = Opml.parse(Opml.write(tree, parentId = null, title = "legirss"))

        assertEquals(
            listOf(
                NewGroup("News", listOf(newChannel("Alpha", "https://alpha.example/feed", "https://alpha.example/"))),
                newChannel("Q&A <\"daily\">", "https://qa.example/feed?a=1&b=2"),
            ),
            nodes,
        )
    }

    /** 親を指定すると、その配下だけを書き出す。 */
    @Test
    fun writesOnlyNodesUnderGivenParent() {
        val nodes = Opml.parse(Opml.write(tree, parentId = 1, title = "News"))

        assertEquals(listOf(newChannel("Alpha", "https://alpha.example/feed", "https://alpha.example/")), nodes)
    }
}
