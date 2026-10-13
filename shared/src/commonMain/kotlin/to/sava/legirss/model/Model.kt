package to.sava.legirss.model

enum class NodeKind(val code: Long) {
    GROUP(0),
    CHANNEL(1),
    ;

    companion object {
        fun fromCode(code: Long): NodeKind = entries.first { it.code == code }
    }
}

enum class ArticleState(val code: Long) {
    UNREAD(0),
    READ(1),
    ;

    companion object {
        fun fromCode(code: Long): ArticleState = entries.first { it.code == code }
    }
}

/** チャンネルであるノードだけが持つ情報。 */
data class ChannelInfo(
    val feedUrl: String,
    val siteUrl: String?,
    val title: String?,
    val paused: Boolean,
    val hasError: Boolean,
)

data class TreeNode(
    val id: Long,
    val parentId: Long?,
    val position: Long,
    val kind: NodeKind,
    val name: String,
    val channel: ChannelInfo?,
)

/** ヘッドラインリストの 1 行。要約と本文は持たない。 */
data class ArticleRow(
    val id: Long,
    val channelId: Long,
    val title: String,
    val link: String?,
    val author: String?,
    val category: String?,
    val publishedAt: Long?,
    val sortAt: Long,
    val state: ArticleState,
)

/** 記事の中身。一覧には載せず、記事を表示するときに読み出す。 */
data class ArticleBody(
    val summary: String?,
    val content: String?,
    val link: String?,
) {
    /** 表示に使う本文。本文があればそれを、無ければ要約を使う。 */
    val text: String? get() = content ?: summary
}

data class ArticleCount(
    val total: Int,
    val unread: Int,
) {
    operator fun plus(other: ArticleCount): ArticleCount =
        ArticleCount(total + other.total, unread + other.unread)

    companion object {
        val ZERO = ArticleCount(0, 0)
    }
}
