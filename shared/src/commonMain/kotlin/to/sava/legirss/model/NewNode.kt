package to.sava.legirss.model

/** これから登録するノード。 */
sealed interface NewNode {
    val name: String
}

data class NewGroup(
    override val name: String,
    val children: List<NewNode>,
) : NewNode

data class NewChannel(
    override val name: String,
    val feedUrl: String,
    val siteUrl: String?,
    val title: String?,
    val paused: Boolean,
    val articles: List<NewArticle>,
) : NewNode

data class NewArticle(
    val identity: String,
    val title: String,
    val link: String?,
    val summary: String?,
    val content: String?,
    val author: String?,
    val category: String?,
    val publishedAt: Long?,
    val sortAt: Long,
    val state: ArticleState,
)
