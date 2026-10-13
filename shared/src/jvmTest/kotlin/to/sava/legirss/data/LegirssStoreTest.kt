package to.sava.legirss.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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
            articles = listOf(article("b1", ArticleState.PROTECTED, 30)),
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

    /** チャンネルごとの総数と未読数を数える。保護された記事は未読に数えない。 */
    @Test
    fun countsArticlesPerChannel() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val tree = store.loadTree()
        val counts = store.loadArticleCounts()

        assertEquals(ArticleCount(total = 2, unread = 1), counts[tree.first { it.name == "Alpha" }.id])
        assertEquals(ArticleCount(total = 1, unread = 0), counts[tree.first { it.name == "Beta" }.id])
    }

    /** チャンネルを指定しなければ全記事を、指定すればそのチャンネルの記事だけを返す。 */
    @Test
    fun loadsArticleRowsByChannel() = runTest {
        val store = newStore().apply { importNodes(nodes) }
        val beta = store.loadTree().first { it.name == "Beta" }

        assertEquals(3, store.loadArticleRows(channelIds = null).size)
        assertEquals(listOf("b1"), store.loadArticleRows(listOf(beta.id)).map { it.title })
    }
}
