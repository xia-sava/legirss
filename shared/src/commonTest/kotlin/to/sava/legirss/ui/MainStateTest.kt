package to.sava.legirss.ui

import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.ChannelInfo
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode
import to.sava.legirss.ui.component.TreeDropPlace
import to.sava.legirss.ui.component.TreeIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainStateTest {
    private fun group(id: Long, parentId: Long?, position: Long, name: String) =
        TreeNode(id, parentId, position, NodeKind.GROUP, name, channel = null)

    private fun channel(id: Long, parentId: Long?, position: Long, name: String, paused: Boolean = false) = TreeNode(
        id = id,
        parentId = parentId,
        position = position,
        kind = NodeKind.CHANNEL,
        name = name,
        channel = ChannelInfo("https://example.com/$id", siteUrl = null, title = null, paused = paused, hasError = false),
    )

    private fun article(
        id: Long,
        channelId: Long,
        state: ArticleState,
        sortAt: Long,
        title: String = "t$id",
        fetchedAt: Long = sortAt,
    ) = ArticleRow(
        id = id,
        channelId = channelId,
        title = title,
        link = null,
        author = null,
        category = null,
        publishedAt = null,
        sortAt = sortAt,
        fetchedAt = fetchedAt,
        state = state,
    )

    // News ─ Alpha(未読 1 / 2 件), Sub ─ Beta(購読中止, 0 / 1 件)。最上位に Gamma(記事なし)。
    private val tree = TreeModel(
        nodes = listOf(
            channel(5, parentId = null, position = 1, name = "Gamma"),
            group(1, parentId = null, position = 0, name = "News"),
            group(3, parentId = 1, position = 1, name = "Sub"),
            channel(2, parentId = 1, position = 0, name = "Alpha"),
            channel(4, parentId = 3, position = 0, name = "Beta", paused = true),
        ),
        channelCounts = mapOf(2L to ArticleCount(total = 2, unread = 1), 4L to ArticleCount(total = 1, unread = 0)),
    )

    private fun destination(sourceId: Long, target: NodeSelection, place: TreeDropPlace) =
        tree.moveDestination(NodeSelection.Node(sourceId), target, place)

    /** 行の前後へ落とすとその行の兄弟になり、グループの中へ落とすとその末尾に入る。位置は移すノードを除いて数える。 */
    @Test
    fun computesDestinationOfMove() {
        assertEquals(MoveDestination(parentId = 1, index = 0), destination(5, NodeSelection.Node(2), TreeDropPlace.BEFORE))
        assertEquals(MoveDestination(parentId = 1, index = 1), destination(5, NodeSelection.Node(2), TreeDropPlace.AFTER))
        assertEquals(MoveDestination(parentId = 3, index = 1), destination(5, NodeSelection.Node(3), TreeDropPlace.INTO))
        assertEquals(MoveDestination(parentId = 1, index = 1), destination(2, NodeSelection.Node(3), TreeDropPlace.AFTER))
        assertEquals(MoveDestination(parentId = null, index = 1), destination(1, NodeSelection.Node(5), TreeDropPlace.AFTER))
    }

    /** 「全ての記事」へ落とすと最上位の末尾に、チャンネルの中へ落とすとそのチャンネルの直後に置く。 */
    @Test
    fun adjustsDestinationForRowsThatCannotContainNodes() {
        assertEquals(MoveDestination(parentId = null, index = 2), destination(4, NodeSelection.All, TreeDropPlace.INTO))
        assertEquals(MoveDestination(parentId = null, index = 1), destination(5, NodeSelection.All, TreeDropPlace.BEFORE))
        assertEquals(MoveDestination(parentId = 1, index = 1), destination(5, NodeSelection.Node(2), TreeDropPlace.INTO))
    }

    /** 自分自身へ、また自分の配下へは移せない。「全ての記事」は移せない。 */
    @Test
    fun rejectsMoveIntoItself() {
        assertNull(destination(1, NodeSelection.Node(1), TreeDropPlace.INTO))
        assertNull(destination(1, NodeSelection.Node(3), TreeDropPlace.INTO))
        assertNull(destination(1, NodeSelection.Node(4), TreeDropPlace.BEFORE))
        assertNull(tree.moveDestination(NodeSelection.All, NodeSelection.Node(1), TreeDropPlace.INTO))
    }

    /** 更新している最中のチャンネルは、購読中止やエラーの印より優先して、更新中のアイコンで示す。 */
    @Test
    fun showsUpdatingIconForChannelsBeingUpdated() {
        val rows = tree.rows(setOf(NodeSelection.All, NodeSelection.Node(1), NodeSelection.Node(3)), updating = setOf(2L, 4L))

        assertEquals(
            listOf(TreeIcon.ALL, TreeIcon.GROUP, TreeIcon.CHANNEL_UPDATING, TreeIcon.GROUP, TreeIcon.CHANNEL_UPDATING, TreeIcon.CHANNEL),
            rows.map { it.icon },
        )
    }

    /** グループの記事数は配下のチャンネルの合計になり、「全ての記事」は全体の合計になる。 */
    @Test
    fun sumsCountsUpTheTree() {
        assertEquals(ArticleCount(total = 3, unread = 1), tree.countOf(NodeSelection.Node(1)))
        assertEquals(ArticleCount(total = 1, unread = 0), tree.countOf(NodeSelection.Node(3)))
        assertEquals(ArticleCount(total = 3, unread = 1), tree.countOf(NodeSelection.All))
    }

    /** 閉じたノードの子は行に出さず、開いたノードの子は position の順に 1 段深く並べる。 */
    @Test
    fun listsRowsOfExpandedNodesOnly() {
        val collapsed = tree.rows(setOf(NodeSelection.All))
        val expanded = tree.rows(setOf(NodeSelection.All, NodeSelection.Node(1)))

        assertEquals(listOf("全ての記事 (1/3)", "News (1/3)", "Gamma"), collapsed.map { it.label })
        assertEquals(listOf(0, 1, 1), collapsed.map { it.depth })
        assertEquals(
            listOf("全ての記事 (1/3)", "News (1/3)", "Alpha (1/2)", "Sub (0/1)", "Gamma"),
            expanded.map { it.label },
        )
        assertEquals(listOf(0, 1, 2, 2, 1), expanded.map { it.depth })
    }

    /** 未読のあるノードだけを太字にし、種別と購読状態に応じたアイコンを付ける。 */
    @Test
    fun decoratesRows() {
        val rows = tree.rows(setOf(NodeSelection.All, NodeSelection.Node(1), NodeSelection.Node(3))).associateBy { it.label }

        assertTrue(rows.getValue("News (1/3)").bold)
        assertFalse(rows.getValue("Sub (0/1)").bold)
        assertEquals(TreeIcon.GROUP, rows.getValue("News (1/3)").icon)
        assertEquals(TreeIcon.CHANNEL, rows.getValue("Alpha (1/2)").icon)
        assertEquals(TreeIcon.CHANNEL_PAUSED, rows.getValue("Beta (0/1)").icon)
        assertFalse(rows.getValue("Gamma").expandable)
    }

    /** 選んだノードの配下にあるチャンネルを集める。「全ての記事」は絞り込みなしを表す null になる。 */
    @Test
    fun collectsChannelsUnderSelection() {
        assertNull(tree.channelIdsUnder(NodeSelection.All))
        assertEquals(listOf(2L, 4L), tree.channelIdsUnder(NodeSelection.Node(1)))
        assertEquals(listOf(4L), tree.channelIdsUnder(NodeSelection.Node(4)))
    }

    private val articles = listOf(
        article(1, channelId = 2, state = ArticleState.READ, sortAt = 300, title = "b"),
        article(2, channelId = 2, state = ArticleState.UNREAD, sortAt = 100, title = "c"),
        article(3, channelId = 4, state = ArticleState.READ, sortAt = 400, title = "a"),
        article(4, channelId = 4, state = ArticleState.UNREAD, sortAt = 200, title = "d"),
    )

    /** 既定の並びは未読が先で、未読の中もそれ以外の中も新しい順になる。 */
    @Test
    fun putsUnreadFirstByDefault() {
        val arranged = arrangeArticles(articles, StateFilter.ALL, ArticleSort(), tree.channelNames)

        assertEquals(listOf(4L, 2L, 3L, 1L), arranged.map { it.id })
    }

    /** 列を指定するとその列で並べ、向きを反転できる。 */
    @Test
    fun sortsByColumn() {
        val byTitle = arrangeArticles(articles, StateFilter.ALL, ArticleSort(ArticleColumn.TITLE), tree.channelNames)
        val byDateDescending =
            arrangeArticles(articles, StateFilter.ALL, ArticleSort(ArticleColumn.DATE, descending = true), tree.channelNames)

        assertEquals(listOf("a", "b", "c", "d"), byTitle.map { it.title })
        assertEquals(listOf(3L, 1L, 4L, 2L), byDateDescending.map { it.id })
    }

    /** 取得日で並べると、記事の日時ではなく取得した時刻の順になる。同じ時刻に取得した記事は、記事の日時の新しい順になる。 */
    @Test
    fun sortsByFetchedTime() {
        val fetched = listOf(
            article(1, channelId = 2, state = ArticleState.UNREAD, sortAt = 9_000, fetchedAt = 100),
            article(2, channelId = 2, state = ArticleState.UNREAD, sortAt = 50, fetchedAt = 300),
            article(3, channelId = 2, state = ArticleState.UNREAD, sortAt = 80, fetchedAt = 300),
            article(4, channelId = 2, state = ArticleState.UNREAD, sortAt = 70, fetchedAt = 200),
        )

        val arranged = arrangeArticles(fetched, StateFilter.ALL, ArticleSort(ArticleColumn.FETCHED, descending = true), tree.channelNames)

        assertEquals(listOf(3L, 2L, 4L, 1L), arranged.map { it.id })
    }

    /** 購読状況で絞り込むと、その状況の記事だけが残る。 */
    @Test
    fun filtersByState() {
        val unread = arrangeArticles(articles, StateFilter.UNREAD, ArticleSort(), tree.channelNames)
        val read = arrangeArticles(articles, StateFilter.READ, ArticleSort(), tree.channelNames)

        assertEquals(listOf(4L, 2L), unread.map { it.id })
        assertEquals(listOf(3L, 1L), read.map { it.id })
    }

    /** 読み始める記事は一覧で一番下にある未読とする。日時が同じ未読が並んでいても、下の行を選ぶ。 */
    @Test
    fun findsLastUnreadByPosition() {
        val sameTime = listOf(
            article(1, channelId = 2, state = ArticleState.UNREAD, sortAt = 100),
            article(2, channelId = 2, state = ArticleState.UNREAD, sortAt = 100),
            article(3, channelId = 2, state = ArticleState.UNREAD, sortAt = 100),
            article(4, channelId = 2, state = ArticleState.READ, sortAt = 50),
        )

        assertEquals(2, MainUiState(articles = sameTime).lastUnreadIndex)
        assertNull(MainUiState(articles = sameTime.takeLast(1)).lastUnreadIndex)
    }

    /** 同じ列を選ぶと向きが反転し、別の列を選ぶと向きを保ったまま列だけが変わる。 */
    @Test
    fun togglesSortDirectionOnSameColumn() {
        val sort = ArticleSort(ArticleColumn.DATE, descending = true)

        assertEquals(ArticleSort(ArticleColumn.DATE, descending = false), sort.toggledBy(ArticleColumn.DATE))
        assertEquals(ArticleSort(ArticleColumn.TITLE, descending = true), sort.toggledBy(ArticleColumn.TITLE))
    }
}
