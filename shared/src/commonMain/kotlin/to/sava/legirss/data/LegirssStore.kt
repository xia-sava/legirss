package to.sava.legirss.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.sava.legirss.data.db.LegirssDatabase
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.ChannelInfo
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.NewGroup
import to.sava.legirss.model.NewNode
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode

/** データベースへの読み書きの窓口。 */
class LegirssStore(
    private val database: LegirssDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = database.schemaQueries

    suspend fun isEmpty(): Boolean = withContext(ioDispatcher) {
        queries.countNodes().executeAsOne() == 0L
    }

    suspend fun loadTree(): List<TreeNode> = withContext(ioDispatcher) {
        queries.selectNodes { id, parentId, position, kind, name, feedUrl, siteUrl, title, paused, errorKind ->
            TreeNode(
                id = id,
                parentId = parentId,
                position = position,
                kind = NodeKind.fromCode(kind),
                name = name,
                channel = feedUrl?.let {
                    ChannelInfo(
                        feedUrl = it,
                        siteUrl = siteUrl,
                        title = title,
                        paused = paused == 1L,
                        hasError = errorKind != null && errorKind != 0L,
                    )
                },
            )
        }.executeAsList()
    }

    /** チャンネルのノード ID ごとの記事数。記事の無いチャンネルは含まれない。 */
    suspend fun loadArticleCounts(): Map<Long, ArticleCount> = withContext(ioDispatcher) {
        queries.selectArticleCounts { channelId, total, unread ->
            channelId to ArticleCount(total = total.toInt(), unread = unread?.toInt() ?: 0)
        }.executeAsList().toMap()
    }

    /** [channelIds] が null なら全チャンネルの記事を返す。 */
    suspend fun loadArticleRows(channelIds: Collection<Long>?): List<ArticleRow> = withContext(ioDispatcher) {
        when (channelIds) {
            null -> queries.selectArticleRows(::toArticleRow)
            else -> queries.selectArticleRowsByChannels(channelIds, ::toArticleRow)
        }.executeAsList()
    }

    /** [nodes] を最上位に登録する。 */
    suspend fun importNodes(nodes: List<NewNode>) = withContext(ioDispatcher) {
        database.transaction { insertNodes(parentId = null, nodes) }
    }

    private fun insertNodes(parentId: Long?, nodes: List<NewNode>) {
        nodes.forEachIndexed { index, node ->
            when (node) {
                is NewGroup -> {
                    queries.insertNode(parentId, index.toLong(), NodeKind.GROUP.code, node.name)
                    insertNodes(queries.lastInsertRowId().executeAsOne(), node.children)
                }

                is NewChannel -> {
                    queries.insertNode(parentId, index.toLong(), NodeKind.CHANNEL.code, node.name)
                    insertChannel(queries.lastInsertRowId().executeAsOne(), node)
                }
            }
        }
    }

    private fun insertChannel(nodeId: Long, channel: NewChannel) {
        queries.insertChannel(nodeId, channel.feedUrl, channel.siteUrl, channel.title, if (channel.paused) 1L else 0L)
        channel.articles.forEach { article ->
            queries.insertArticle(
                channel_id = nodeId,
                identity = article.identity,
                title = article.title,
                link = article.link,
                summary = article.summary,
                content = article.content,
                author = article.author,
                category = article.category,
                published_at = article.publishedAt,
                sort_at = article.sortAt,
                state = article.state.code,
            )
        }
    }

    private fun toArticleRow(
        id: Long,
        channelId: Long,
        title: String,
        link: String?,
        author: String?,
        category: String?,
        publishedAt: Long?,
        sortAt: Long,
        state: Long,
    ): ArticleRow = ArticleRow(id, channelId, title, link, author, category, publishedAt, sortAt, ArticleState.fromCode(state))
}
