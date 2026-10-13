package to.sava.legirss.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NewArticle
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.NewGroup
import to.sava.legirss.model.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegirssStoreTest {
    private fun newStore() = LegirssStore(DatabaseFactory.openInMemory(), Dispatchers.Unconfined)

    private fun article(title: String, state: ArticleState, sortAt: Long) = NewArticle(
        identity = title,
        title = title,
        link = null,
        summary = null,
        content = null,
        author = null,
        category = null,
        publishedAt = null,
        sortAt = sortAt,
        state = state,
    )

    private val nodes = listOf(
        NewGroup(
            name = "News",
            children = listOf(
                NewChannel(
                    name = "Alpha",
                    feedUrl = "https://alpha.example/feed",
                    siteUrl = null,
                    title = null,
                    paused = false,
                    firstFetchAsRead = false,
                    articles = listOf(
                        article("a1", ArticleState.UNREAD, 10),
                        article("a2", ArticleState.READ, 20),
                    ),
                ),
            ),
        ),
        NewChannel(
            name = "Beta",
            feedUrl = "https://beta.example/rss",
            siteUrl = null,
            title = null,
            paused = true,
            firstFetchAsRead = false,
            articles = listOf(article("b1", ArticleState.READ, 30)),
        ),
    )

    /** 作ったばかりのデータベースは空と判定される。 */
    @Test
    fun startsEmpty() = runTest {
        assertTrue(newStore().isEmpty())
    }

    /** 登録した木を、親子関係・兄弟の順番・チャンネルの情報を保ったまま読み出せる。 */
    @Test
    fun importsTree() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val tree = store.loadTree()

        assertFalse(store.isEmpty())
        assertEquals(listOf("News", "Beta", "Alpha"), tree.map { it.name })
        val news = tree.first { it.name == "News" }
        val alpha = tree.first { it.name == "Alpha" }
        val beta = tree.first { it.name == "Beta" }
        assertEquals(NodeKind.GROUP, news.kind)
        assertNull(news.channel)
        assertEquals(news.id, alpha.parentId)
        assertEquals(1L, beta.position)
        assertTrue(beta.channel!!.paused)
    }

    /** チャンネルごとの総数と未読数を数える。既読の記事は未読に数えない。 */
    @Test
    fun countsArticlesPerChannel() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val tree = store.loadTree()
        val counts = store.loadArticleCounts()

        assertEquals(ArticleCount(total = 2, unread = 1), counts[tree.first { it.name == "Alpha" }.id])
        assertEquals(ArticleCount(total = 1, unread = 0), counts[tree.first { it.name == "Beta" }.id])
    }

    /** ファイルのデータベースに複数のスレッドから同時に書き込んでも、全ての書き込みが成功する。 */
    @Test
    fun acceptsConcurrentWritesToFileDatabase() = runBlocking {
        val dataDir = Files.createTempDirectory("legirss-store-test")
        val store = LegirssStore(DatabaseFactory.open(dataDir), Dispatchers.IO)
        val writers = 40

        (1..writers).map { number ->
            async(Dispatchers.IO) {
                store.importNodes(
                    listOf(
                        NewChannel(
                            name = "Channel $number",
                            feedUrl = "https://example.com/$number",
                            siteUrl = null,
                            title = null,
                            paused = false,
                            firstFetchAsRead = false,
                            articles = (1..20).map { article("a$it", ArticleState.UNREAD, it.toLong()) },
                        ),
                    ),
                )
            }
        }.awaitAll()

        assertEquals(writers, store.loadTree().size)
        assertEquals(writers * 20, store.loadArticleRows(channelIds = null).size)
    }

    /** チャンネルを指定しなければ全記事を、指定すればそのチャンネルの記事だけを返す。 */
    @Test
    fun loadsArticleRowsByChannel() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val beta = store.loadTree().first { it.name == "Beta" }

        assertEquals(3, store.loadArticleRows(channelIds = null).size)
        assertEquals(listOf("b1"), store.loadArticleRows(listOf(beta.id)).map { it.title })
    }

    /** [parentId] の直下のノードの名前を、並び順で返す。 */
    private suspend fun LegirssStore.childNames(parentId: Long?): List<String> =
        loadTree().filter { it.parentId == parentId }.sortedBy { it.position }.map { it.name }

    private suspend fun LegirssStore.idOf(name: String): Long = loadTree().first { it.name == name }.id

    /** 追加したグループとチャンネルは、指定した親の下の末尾に入る。 */
    @Test
    fun addsNodesAtEndOfParent() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val news = store.idOf("News")

        store.addGroup(parentId = null, name = "Tech")
        store.addChannel(parentId = news, name = "Gamma", feedUrl = "https://gamma.example/feed")

        assertEquals(listOf("News", "Beta", "Tech"), store.childNames(parentId = null))
        assertEquals(listOf("Alpha", "Gamma"), store.childNames(news))
    }

    /** グループを削除すると、配下のチャンネルとその記事も消える。ほかのノードは残る。 */
    @Test
    fun deletesGroupWithItsContent() = runTest {
        val store = newStore().apply { importNodes(nodes) }

        store.deleteNode(store.idOf("News"))

        assertEquals(listOf("Beta"), store.loadTree().map { it.name })
        assertEquals(listOf("b1"), store.loadArticleRows(channelIds = null).map { it.title })
    }

    /** 名前とフィードの URL を書き換えられる。 */
    @Test
    fun renamesNodeAndChangesFeedUrl() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val alpha = store.idOf("Alpha")

        store.renameNode(alpha, "Alpha Daily")
        store.setFeedUrl(alpha, "https://alpha.example/new")

        val renamed = store.loadTree().first { it.id == alpha }
        assertEquals("Alpha Daily", renamed.name)
        assertEquals("https://alpha.example/new", renamed.channel?.feedUrl)
    }

    /** ノードを別の親の下の指定の位置へ移せる。同じ親の中での並べ替えもできる。 */
    @Test
    fun movesNodeToGivenPlace() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val news = store.idOf("News")
        store.addChannel(parentId = news, name = "Gamma", feedUrl = "https://gamma.example/feed")

        store.moveNode(store.idOf("Beta"), parentId = news, index = 1)
        val afterMoveIntoGroup = store.childNames(news)
        store.moveNode(store.idOf("Gamma"), parentId = news, index = 0)
        val afterReorder = store.childNames(news)
        store.moveNode(store.idOf("Alpha"), parentId = null, index = 0)

        assertEquals(listOf("Alpha", "Beta", "Gamma"), afterMoveIntoGroup)
        assertEquals(listOf("Gamma", "Alpha", "Beta"), afterReorder)
        assertEquals(listOf("Alpha", "News"), store.childNames(parentId = null))
        assertEquals(listOf("Gamma", "Beta"), store.childNames(news))
    }

    /** 直下のノードを、大文字と小文字を区別せずに名前の順へ並べ直す。 */
    @Test
    fun sortsChildrenByName() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        store.addGroup(parentId = null, name = "alpha group")

        store.sortChildrenByName(parentId = null)

        assertEquals(listOf("alpha group", "Beta", "News"), store.childNames(parentId = null))
    }
}
