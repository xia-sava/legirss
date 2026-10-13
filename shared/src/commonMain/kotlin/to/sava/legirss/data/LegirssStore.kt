package to.sava.legirss.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.sava.legirss.data.db.LegirssDatabase
import to.sava.legirss.feed.ParsedFeed
import to.sava.legirss.model.AppSettings
import to.sava.legirss.model.ArticleBody
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.ChannelError
import to.sava.legirss.model.ChannelInfo
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.NewGroup
import to.sava.legirss.model.NewNode
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.Retention
import to.sava.legirss.model.TreeNode
import to.sava.legirss.model.UpdateTarget

/** データベースへの読み書きの窓口。 */
class LegirssStore(
    private val database: LegirssDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = database.schemaQueries

    private val accessLock = Mutex()

    /**
     * データベースへの操作を 1 つずつ順に実行する。
     *
     * ドライバはスレッドごとに別の接続を使うので、並行して書き込むと互いにロックして失敗する。
     */
    private suspend fun <T> access(block: () -> T): T =
        withContext(context = ioDispatcher) { accessLock.withLock { block() } }

    /** ほかの読み書きが割り込まない状態で [block] を実行する。データベースのファイルを丸ごと扱う処理に使う。 */
    suspend fun <T> withExclusiveAccess(block: () -> T): T = access(block)

    suspend fun isEmpty(): Boolean = access {
        queries.countNodes().executeAsOne() == 0L
    }

    suspend fun loadTree(): List<TreeNode> = access {
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
    suspend fun loadArticleCounts(): Map<Long, ArticleCount> = access {
        queries.selectArticleCounts { channelId, total, unread ->
            channelId to ArticleCount(total = total.toInt(), unread = unread?.toInt() ?: 0)
        }.executeAsList().toMap()
    }

    /** [channelIds] が null なら全チャンネルの記事を返す。 */
    suspend fun loadArticleRows(channelIds: Collection<Long>?): List<ArticleRow> = access {
        when (channelIds) {
            null -> queries.selectArticleRows(::toArticleRow)
            else -> queries.selectArticleRowsByChannels(channelIds, ::toArticleRow)
        }.executeAsList()
    }

    /** 設定の値を読み出す。保存されていなければ null。 */
    suspend fun loadSetting(key: String): String? = access {
        queries.selectSetting(key).executeAsOneOrNull()
    }

    suspend fun saveSetting(key: String, value: String) = access {
        queries.upsertSetting(key, value)
    }

    /** 保存されている全ての設定を、名前と値の組で返す。 */
    suspend fun loadSettings(): Map<String, String> = access {
        queries.selectSettings { key, value -> key to value }.executeAsList().toMap()
    }

    /** [settings] の設定をまとめて保存する。ここに無い名前の設定は変えない。 */
    suspend fun saveSettings(settings: Map<String, String>) = access {
        database.transaction { settings.forEach { (key, value) -> queries.upsertSetting(key, value) } }
    }

    /** 記事の要約と本文を読み出す。記事が無ければ null。 */
    suspend fun loadArticleBody(articleId: Long): ArticleBody? = access {
        queries.selectArticleBody(articleId) { summary, content, link -> ArticleBody(summary, content, link) }
            .executeAsOneOrNull()
    }

    /** 購読中のチャンネルを更新の対象として返す。[channelIds] が null でなければ、その中のものに限る。 */
    suspend fun loadUpdateTargets(channelIds: Collection<Long>?): List<UpdateTarget> = access {
        val wanted = channelIds?.toSet()
        queries.selectUpdateTargets { nodeId, feedUrl, etag, lastModified, firstFetchAsRead, goneCount, goneAt ->
            UpdateTarget(nodeId, feedUrl, etag, lastModified, firstFetchAsRead == 1L, goneCount.toInt(), goneAt)
        }.executeAsList().filter { wanted == null || it.channelId in wanted }
    }

    /**
     * 取得したフィードをチャンネルに取り込む。
     *
     * 登録済みの記事は内容だけを更新し、閲覧状況は変えない。整理・削除した記事は登録し直さない。
     * 取り込んだあと、[retention] に従って古い記事を整理する。
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
        retention: Retention,
        now: Long,
    ): Int = access {
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
            removeExpiredArticles(channelId, retention, now)
            // フィードに載っている識別子は、上で見かけた時刻を新しくしてある。
            queries.deleteStaleSeen(channelId, now - SEEN_KEEP_MILLIS)
            if (target.firstFetchAsRead) 0 else added
        }
    }

    /** フィードに変更が無かったことを記録し、[retention] に従って古い記事を整理する。 */
    suspend fun recordNotModified(target: UpdateTarget, feedUrl: String, retention: Retention, now: Long) = access {
        database.transaction {
            queries.recordNotModified(feedUrl, now, target.channelId)
            removeExpiredArticles(target.channelId, retention, now)
        }
    }

    /** 残す範囲から外れた既読の記事を消す。フィードに残っていても登録し直されないよう、識別子を控えておく。 */
    private fun removeExpiredArticles(channelId: Long, retention: Retention, now: Long) {
        queries.selectExpiredArticleIds(channelId, now - retention.keepDays * MILLIS_PER_DAY, retention.keepCount.toLong())
            .executeAsList()
            .forEach { articleId ->
                queries.rememberDeletedArticle(now, articleId)
                queries.deleteArticle(articleId)
            }
    }

    /** 既読の記事を残す範囲の設定。保存されていない項目は既定値になる。 */
    suspend fun loadRetention(): Retention = AppSettings.fromSettings(loadSettings()).retention

    /** 更新の失敗を記録する。[paused] が true なら購読中止にする。 */
    suspend fun recordFetchFailure(
        target: UpdateTarget,
        error: ChannelError,
        detail: String,
        goneCount: Int,
        goneAt: Long?,
        paused: Boolean,
        now: Long,
    ) = access {
        queries.recordFetchFailure(now, error.code, detail, goneCount.toLong(), goneAt, if (paused) 1L else 0L, target.channelId)
    }

    suspend fun setArticleState(articleId: Long, state: ArticleState) = setArticleStates(listOf(articleId), state)

    suspend fun setArticleStates(articleIds: Collection<Long>, state: ArticleState) = access {
        database.transaction { articleIds.forEach { queries.updateArticleState(state.code, it) } }
    }

    suspend fun deleteArticle(articleId: Long, now: Long) = deleteArticles(listOf(articleId), now)

    /** 記事を削除する。フィードに残っていても登録し直されないよう、識別子を控えておく。 */
    suspend fun deleteArticles(articleIds: Collection<Long>, now: Long) = access {
        database.transaction {
            articleIds.forEach { articleId ->
                queries.rememberDeletedArticle(now, articleId)
                queries.deleteArticle(articleId)
            }
        }
    }

    /**
     * チャンネルを [parentId] の下の末尾に登録する。[parentId] が null なら最上位に置く。
     *
     * @return 登録したチャンネルのノード ID。
     */
    suspend fun addChannel(parentId: Long?, name: String, feedUrl: String): Long = access {
        database.transactionWithResult {
            queries.insertNode(parentId, queries.selectNextPosition(parentId).executeAsOne(), NodeKind.CHANNEL.code, name)
            queries.lastInsertRowId().executeAsOne().also { nodeId ->
                queries.insertChannel(nodeId, feedUrl, site_url = null, title = null, paused = 0L, first_fetch_as_read = 0L)
            }
        }
    }

    /**
     * グループを [parentId] の下の末尾に登録する。[parentId] が null なら最上位に置く。
     *
     * @return 登録したグループのノード ID。
     */
    suspend fun addGroup(parentId: Long?, name: String): Long = access {
        database.transactionWithResult {
            queries.insertNode(parentId, queries.selectNextPosition(parentId).executeAsOne(), NodeKind.GROUP.code, name)
            queries.lastInsertRowId().executeAsOne()
        }
    }

    suspend fun renameNode(nodeId: Long, name: String) = access {
        queries.updateNodeName(name, nodeId)
    }

    /** ノードを削除する。グループなら配下のノードも、チャンネルなら記事も一緒に消える。 */
    suspend fun deleteNode(nodeId: Long) = access {
        queries.deleteNode(nodeId)
    }

    suspend fun setFeedUrl(channelId: Long, feedUrl: String) = access {
        queries.updateFeedUrl(feedUrl, channelId)
    }

    /**
     * ノードを [parentId] の下の、先頭から [index] 番目へ移す。[parentId] が null なら最上位へ移す。
     *
     * [index] は移すノードを除いた並びでの位置。範囲を外れていれば、先頭または末尾に置く。
     */
    suspend fun moveNode(nodeId: Long, parentId: Long?, index: Int) = access {
        database.transaction {
            val siblings = queries.selectChildren(parentId) { id, _ -> id }.executeAsList() - nodeId
            val position = index.coerceIn(0, siblings.size)
            placeInOrder(parentId, siblings.take(position) + nodeId + siblings.drop(position))
        }
    }

    /** [parentId] の直下のノードを名前の順に並べ直す。大文字と小文字は区別しない。 */
    suspend fun sortChildrenByName(parentId: Long?) = access {
        database.transaction {
            val children = queries.selectChildren(parentId) { id, name -> id to name }.executeAsList()
            placeInOrder(parentId, children.sortedBy { (_, name) -> name.lowercase() }.map { (id, _) -> id })
        }
    }

    private fun placeInOrder(parentId: Long?, nodeIds: List<Long>) {
        nodeIds.forEachIndexed { position, nodeId -> queries.updateNodePlace(parentId, position.toLong(), nodeId) }
    }

    /** チャンネルを購読中止にする、または購読を再開する。 */
    suspend fun setChannelsPaused(channelIds: Collection<Long>, paused: Boolean) = access {
        queries.setChannelsPaused(if (paused) 1L else 0L, channelIds)
    }

    /** [nodes] を最上位に登録する。 */
    suspend fun importNodes(nodes: List<NewNode>) = access {
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

    private companion object {
        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

        /** 整理・削除した記事の識別子を、フィードで見かけなくなってから控えておく期間。 */
        const val SEEN_KEEP_MILLIS = 30 * MILLIS_PER_DAY
    }
}
