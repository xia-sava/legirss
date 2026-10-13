package to.sava.legirss.ui

import to.sava.legirss.feed.FeedCandidate
import to.sava.legirss.html.HtmlBlock
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode
import to.sava.legirss.ui.component.TreeIcon
import to.sava.legirss.ui.component.TreeRow
import to.sava.legirss.update.UpdateProgress

/** ツリーで選んでいるもの。 */
sealed interface NodeSelection {
    /** 先頭の「全ての記事」。 */
    data object All : NodeSelection

    data class Node(val id: Long) : NodeSelection
}

enum class StateFilter(val label: String) {
    ALL("全て"),
    UNREAD("未読"),
    READ("既読"),
    PROTECTED("保護"),
    ;

    fun accepts(state: ArticleState): Boolean = when (this) {
        ALL -> true
        UNREAD -> state == ArticleState.UNREAD
        READ -> state == ArticleState.READ
        PROTECTED -> state == ArticleState.PROTECTED
    }
}

enum class ArticleColumn(val title: String) {
    STATE(""),
    DATE("更新日"),
    TITLE("タイトル"),
    CHANNEL("チャンネル"),
    CATEGORY("カテゴリ"),
    AUTHOR("作者"),
}

/** 一覧の並べ替え。既定は閲覧状況順（未読が先）で、同じ状況の中は新しい順。 */
data class ArticleSort(
    val column: ArticleColumn = ArticleColumn.STATE,
    val descending: Boolean = false,
) {
    /** 同じ列を選んだら向きを反転し、別の列を選んだら向きはそのままにする。 */
    fun toggledBy(clicked: ArticleColumn): ArticleSort =
        if (clicked == column) copy(descending = !descending) else copy(column = clicked)
}

/** 表示用に解釈した記事の本文。 */
data class ArticleContent(
    val articleId: Long,
    val blocks: List<HtmlBlock>,
)

/** 画面の手前に出して、入力や確認を求める窓。 */
sealed interface DialogState {
    /** チャンネルの追加。 */
    data class AddChannel(
        /** 入力された URL を確かめている最中か。 */
        val busy: Boolean = false,
        val message: String? = null,
        /** 入力されたページが案内していたフィード。ここから選ばせる。 */
        val candidates: List<FeedCandidate> = emptyList(),
        /** 登録済みだと知らせたフィードの URL。同じフィードをもう一度指定されたら、重ねて登録する。 */
        val duplicateUrl: String? = null,
    ) : DialogState
}

data class MainUiState(
    val treeRows: List<TreeRow<NodeSelection>> = emptyList(),
    val selectedNode: NodeSelection = NodeSelection.All,
    val selectedNodeName: String = ALL_ARTICLES_LABEL,
    val selectedNodeCount: ArticleCount = ArticleCount.ZERO,
    /** 全チャンネルを合わせた記事数。 */
    val totalCount: ArticleCount = ArticleCount.ZERO,
    val articles: List<ArticleRow> = emptyList(),
    val selectedArticleIndex: Int? = null,
    val channelNames: Map<Long, String> = emptyMap(),
    val filter: StateFilter = StateFilter.ALL,
    val sort: ArticleSort = ArticleSort(),
    /** 選んでいる記事の本文。読み出しが済むまでと、記事を選んでいない間は null。 */
    val articleContent: ArticleContent? = null,
    /** 実行中の更新の進み具合。実行していなければ null。 */
    val updateProgress: UpdateProgress? = null,
    /** 直近の更新の結果など、ステータスバーに出す知らせ。 */
    val statusMessage: String? = null,
    /** 値が変わるたびに、記事の一覧へフォーカスを移す。 */
    val listFocusRequest: Int = 0,
    /** 手前に出している窓。出していなければ null。 */
    val dialog: DialogState? = null,
) {
    val selectedArticle: ArticleRow? get() = selectedArticleIndex?.let(articles::getOrNull)

    /**
     * 一覧で一番下にある未読の記事の位置。未読が無ければ null。
     *
     * 既定の並びでは未読が新しい順に上へ集まるので、これが読み始める記事（一番古い未読）になる。
     * 日時が同じ記事が並んでいても迷わないよう、日時ではなく一覧での位置で決める。
     */
    val lastUnreadIndex: Int?
        get() = articles.indexOfLast { it.state == ArticleState.UNREAD }.takeIf { it >= 0 }
}

/** ←→ のキーで閲覧状況を切り替える順番。 */
private val stateCycle = listOf(ArticleState.UNREAD, ArticleState.READ, ArticleState.PROTECTED)

/** 閲覧状況を 未読 → 既読 → 保護 → 未読 の順（[forward] が false なら逆順）で 1 つ進めた値。 */
fun ArticleState.cycled(forward: Boolean): ArticleState =
    stateCycle[(stateCycle.indexOf(this) + (if (forward) 1 else -1)).mod(stateCycle.size)]

/** 購読状況の絞り込みを 全て → 未読 → 既読 → 保護 → 全て の順で 1 つ進めた値。 */
fun StateFilter.next(): StateFilter = StateFilter.entries[(ordinal + 1) % StateFilter.entries.size]

const val ALL_ARTICLES_LABEL = "全ての記事"

/** ツリーの構造から、表示用の行と記事数を求める。 */
class TreeModel(
    nodes: List<TreeNode>,
    private val channelCounts: Map<Long, ArticleCount>,
) {
    private val byId = nodes.associateBy { it.id }
    private val childrenByParent = nodes.groupBy { it.parentId }.mapValues { (_, children) -> children.sortedBy { it.position } }
    private val countsById = mutableMapOf<Long, ArticleCount>()

    val totalCount: ArticleCount = topLevel().fold(ArticleCount.ZERO) { total, node -> total + countOf(node) }

    val channelNames: Map<Long, String> = nodes.filter { it.kind == NodeKind.CHANNEL }.associate { it.id to it.name }

    fun nameOf(selection: NodeSelection): String = when (selection) {
        NodeSelection.All -> ALL_ARTICLES_LABEL
        is NodeSelection.Node -> byId[selection.id]?.name.orEmpty()
    }

    fun countOf(selection: NodeSelection): ArticleCount = when (selection) {
        NodeSelection.All -> totalCount
        is NodeSelection.Node -> byId[selection.id]?.let(::countOf) ?: ArticleCount.ZERO
    }

    fun nodeOf(selection: NodeSelection): TreeNode? = (selection as? NodeSelection.Node)?.let { byId[it.id] }

    /**
     * [selection] を選んでいるときに足すノードの親。最上位に足すなら null。
     *
     * グループを選んでいればその中に、チャンネルを選んでいればそれと同じ親の下に足す。
     */
    fun parentForNewNode(selection: NodeSelection): Long? =
        nodeOf(selection)?.let { node -> if (node.kind == NodeKind.GROUP) node.id else node.parentId }

    /** [feedUrl] のフィードを購読するチャンネルが登録されているか。 */
    fun hasFeed(feedUrl: String): Boolean = byId.values.any { it.channel?.feedUrl == feedUrl }

    /** [selection] の配下にあるチャンネルの ID。「全ての記事」なら null（絞り込まない）。 */
    fun channelIdsUnder(selection: NodeSelection): List<Long>? = when (selection) {
        NodeSelection.All -> null
        is NodeSelection.Node -> byId[selection.id]?.let(::channelsUnder).orEmpty()
    }

    fun rows(expanded: Set<NodeSelection>): List<TreeRow<NodeSelection>> = buildList {
        val rootExpanded = NodeSelection.All in expanded
        add(
            TreeRow(
                key = NodeSelection.All,
                depth = 0,
                label = labelOf(ALL_ARTICLES_LABEL, totalCount),
                bold = totalCount.unread > 0,
                icon = TreeIcon.ALL,
                expandable = topLevel().isNotEmpty(),
                expanded = rootExpanded,
            ),
        )
        if (rootExpanded) topLevel().forEach { addRows(it, depth = 1, expanded) }
    }

    private fun MutableList<TreeRow<NodeSelection>>.addRows(node: TreeNode, depth: Int, expanded: Set<NodeSelection>) {
        val key = NodeSelection.Node(node.id)
        val children = childrenOf(node)
        val count = countOf(node)
        add(
            TreeRow(
                key = key,
                depth = depth,
                label = labelOf(node.name, count),
                bold = count.unread > 0,
                icon = iconOf(node),
                expandable = children.isNotEmpty(),
                expanded = key in expanded,
            ),
        )
        if (key in expanded) children.forEach { addRows(it, depth + 1, expanded) }
    }

    private fun topLevel(): List<TreeNode> = childrenByParent[null].orEmpty()

    private fun childrenOf(node: TreeNode): List<TreeNode> = childrenByParent[node.id].orEmpty()

    private fun countOf(node: TreeNode): ArticleCount = countsById.getOrPut(node.id) {
        when (node.kind) {
            NodeKind.CHANNEL -> channelCounts[node.id] ?: ArticleCount.ZERO
            NodeKind.GROUP -> childrenOf(node).fold(ArticleCount.ZERO) { total, child -> total + countOf(child) }
        }
    }

    private fun channelsUnder(node: TreeNode): List<Long> = when (node.kind) {
        NodeKind.CHANNEL -> listOf(node.id)
        NodeKind.GROUP -> childrenOf(node).flatMap(::channelsUnder)
    }

    private fun iconOf(node: TreeNode): TreeIcon = when {
        node.channel == null -> TreeIcon.GROUP
        node.channel.paused -> TreeIcon.CHANNEL_PAUSED
        node.channel.hasError -> TreeIcon.CHANNEL_ERROR
        else -> TreeIcon.CHANNEL
    }

    private fun labelOf(name: String, count: ArticleCount): String =
        if (count.total == 0) name else "$name (${count.unread}/${count.total})"
}

/** 一覧に出す記事を絞り込み、並べ替える。 */
fun arrangeArticles(
    rows: List<ArticleRow>,
    filter: StateFilter,
    sort: ArticleSort,
    channelNames: Map<Long, String>,
): List<ArticleRow> {
    val primary: Comparator<ArticleRow> = when (sort.column) {
        ArticleColumn.STATE -> compareBy { if (it.state == ArticleState.UNREAD) 0 else 1 }
        ArticleColumn.DATE -> compareBy { it.sortAt }
        ArticleColumn.TITLE -> compareBy { it.title }
        ArticleColumn.CHANNEL -> compareBy { channelNames[it.channelId].orEmpty() }
        ArticleColumn.CATEGORY -> compareBy { it.category.orEmpty() }
        ArticleColumn.AUTHOR -> compareBy { it.author.orEmpty() }
    }
    val ordered = if (sort.descending) primary.reversed() else primary
    return rows
        .filter { filter.accepts(it.state) }
        .sortedWith(ordered.thenByDescending { it.sortAt }.thenBy { it.id })
}
