package to.sava.legirss.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.sava.legirss.data.db.LegirssDatabase
import to.sava.legirss.feed.ParsedFeed
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.ChannelError
import to.sava.legirss.model.ChannelInfo
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.NewGroup
import to.sava.legirss.model.NewNode
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode
import to.sava.legirss.model.UpdateTarget

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

    /** 購読中のチャンネルを更新の対象として返す。[channelIds] が null でなければ、その中のものに限る。 */
    suspend fun loadUpdateTargets(channelIds: Collection<Long>?): List<UpdateTarget> = withContext(ioDispatcher) {
        val wanted = channelIds?.toSet()
        queries.selectUpdateTargets { nodeId, feedUrl, etag, lastModified, firstFetchAsRead, goneCount, goneAt ->
            UpdateTarget(nodeId, feedUrl, etag, lastModified, firstFetchAsRead == 1L, goneCount.toInt(), goneAt)
        }.executeAsList().filter { wanted == null || it.channelId in wanted }
    }

    /**
     * 取得したフィードをチャンネルに取り込む。
     *
     * 登録済みの記事は内容だけを更新し、閲覧状況は変えない。整理・削除した記事は登録し直さない。
     *
     * @param feedUrl 取得に使った URL。恒久的に移転していれば移転先。
     * @return 未読として新しく登録した記事の数。
     */
    suspend fun applyFetchedFeed(
        target: UpdateTarget,
        feedUrl: String,
        feed: ParsedFeed,
        etag: String?,
        lastModified: String?,
        now: Long,
    ): Int = withContext(ioDispatcher) {
        database.transactionWithResult {
            val channelId = target.channelId
            val registered = queries.selectArticleIdentities(channelId).executeAsList().toSet()
            val removed = queries.selectSeenIdentities(channelId).executeAsList().toSet()
            val state = if (target.firstFetchAsRead) ArticleState.READ else ArticleState.UNREAD
            val added = feed.items.distinctBy { it.identity }.withIndex().count { (index, item) ->
                when (item.identity) {
                    in removed -> queries.touchSeen(now, channelId, item.identity)
                    in registered -> queries.updateArticleContent(
                        title = item.title,
                        link = item.link,
                        summary = item.summary,
                        content = item.content,
                        author = item.author,
                        category = item.category,
                        published_at = item.publishedAt,
                        channel_id = channelId,
                        identity = item.identity,
                    )
                    // 日時の無い記事は取得時刻を使う。フィード内で先にあるものほど新しい側に並べる。
                    else -> queries.insertArticle(
                        channel_id = channelId,
                        identity = item.identity,
                        title = item.title,
                        link = item.link,
                        summary = item.summary,
                        content = item.content,
                        author = item.author,
                        category = item.category,
                        published_at = item.publishedAt,
                        sort_at = item.publishedAt ?: (now - index),
                        state = state.code,
                    )
                }
                item.identity !in removed && item.identity !in registered
            }
            queries.recordFetchSuccess(feedUrl, feed.siteUrl, feed.title, etag, lastModified, now, channelId)
            if (target.firstFetchAsRead) 0 else added
        }
    }

    /** フィードに変更が無かったことを記録する。 */
    suspend fun recordNotModified(target: UpdateTarget, feedUrl: String, now: Long) = withContext(ioDispatcher) {
        queries.recordNotModified(feedUrl, now, target.channelId)
    }

    /** 更新の失敗を記録する。[paused] が true なら購読中止にする。 */
    suspend fun recordFetchFailure(
        target: UpdateTarget,
        error: ChannelError,
        detail: String,
        goneCount: Int,
        goneAt: Long?,
        paused: Boolean,
        now: Long,
    ) = withContext(ioDispatcher) {
        queries.recordFetchFailure(now, error.code, detail, goneCount.toLong(), goneAt, if (paused) 1L else 0L, target.channelId)
    }

    suspend fun setArticleState(articleId: Long, state: ArticleState) = withContext(ioDispatcher) {
        queries.updateArticleState(state.code, articleId)
    }

    /** 記事を削除する。フィードに残っていても登録し直されないよう、識別子を控えておく。 */
    suspend fun deleteArticle(articleId: Long, now: Long) = withContext(ioDispatcher) {
        database.transaction {
            queries.rememberDeletedArticle(now, articleId)
            queries.deleteArticle(articleId)
        }
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
        queries.insertChannel(
            node_id = nodeId,
            feed_url = channel.feedUrl,
            site_url = channel.siteUrl,
            title = channel.title,
            paused = if (channel.paused) 1L else 0L,
            first_fetch_as_read = if (channel.firstFetchAsRead) 1L else 0L,
        )
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
