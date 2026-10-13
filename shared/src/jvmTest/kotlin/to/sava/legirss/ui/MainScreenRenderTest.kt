package to.sava.legirss.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import to.sava.legirss.html.HtmlBlocks
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.ChannelInfo
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode
import to.sava.legirss.ui.theme.LegirssStyle
import to.sava.legirss.ui.theme.LocalLegirssStyle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 何もしない操作。描画だけを確かめるときに渡す。 */
private object NoActions : MainActions {
    override fun selectNode(selection: NodeSelection) = Unit

    override fun toggleNode(selection: NodeSelection) = Unit

    override fun setFilter(filter: StateFilter) = Unit

    override fun cycleFilter() = Unit

    override fun sortBy(column: ArticleColumn) = Unit

    override fun selectArticle(index: Int) = Unit

    override fun readOn() = Unit

    override fun cycleSelectedState(forward: Boolean) = Unit

    override fun deleteSelectedArticle() = Unit

    override fun openSelectedArticle() = Unit

    override fun openLink(url: String) = Unit

    override fun updateAll() = Unit

    override fun updateSelected() = Unit
}

/** メイン画面を画面に出さずに描画し、画像として保存する。 */
internal object MainScreenRenderer {
    const val WIDTH = 1100
    const val HEIGHT = 720
    private const val UI_FONT_NAME = "Yu Gothic UI"

    @OptIn(ExperimentalTextApi::class)
    private val style = LegirssStyle(
        fontFamily = FontFamily(SystemFont(UI_FONT_NAME), SystemFont(UI_FONT_NAME, FontWeight.Bold)),
    )

    fun render(state: MainUiState): Image =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(LocalLegirssStyle provides style) {
                MainScreen(state, NoActions, rememberScrollState())
            }
        }.use { scene ->
            // 遅延リストの中身は 2 回目の描画で確定する。
            scene.render()
            scene.render()
        }

    fun save(image: Image, path: Path) {
        Files.createDirectories(path.parent)
        Files.write(path, requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
    }
}

class MainScreenRenderTest {
    private val nodes = listOf(
        TreeNode(1, parentId = null, position = 0, kind = NodeKind.GROUP, name = "ニュース", channel = null),
        TreeNode(
            id = 2,
            parentId = 1,
            position = 0,
            kind = NodeKind.CHANNEL,
            name = "サンプル通信",
            channel = ChannelInfo("https://example.com/feed", siteUrl = null, title = null, paused = false, hasError = false),
        ),
    )
    private val articles = (1L..40L).map { id ->
        ArticleRow(
            id = id,
            channelId = 2,
            title = "サンプルの記事 $id",
            link = "https://example.com/$id",
            author = "author",
            category = "category",
            publishedAt = id * 3_600_000,
            sortAt = id * 3_600_000,
            state = if (id > 30) ArticleState.UNREAD else ArticleState.READ,
        )
    }

    /** ツリーと一覧を持つ状態を指定の大きさで描画でき、背景以外の画素が描かれる。 */
    @Test
    fun rendersTreeAndList() {
        val tree = TreeModel(nodes, mapOf(2L to ArticleCount(total = 40, unread = 10)))
        val state = MainUiState(
            treeRows = tree.rows(setOf(NodeSelection.All, NodeSelection.Node(1))),
            selectedNodeCount = tree.totalCount,
            articles = arrangeArticles(articles, StateFilter.ALL, ArticleSort(), tree.channelNames),
            selectedArticleIndex = 0,
            channelNames = tree.channelNames,
            articleContent = ArticleContent(
                articleId = 40,
                blocks = HtmlBlocks.parse(
                    "<p>本文の段落。<b>太字</b>と<a href=\"https://example.com/\">リンク</a>を含む。</p>" +
                        "<h2>見出し</h2><ul><li>項目 1</li><li>項目 2</li></ul>" +
                        "<blockquote><p>引用した文。</p></blockquote><pre>val x = 1\nval y = 2</pre><hr><p>末尾。</p>",
                    baseUrl = null,
                ),
            ),
        )

        val image = MainScreenRenderer.render(state)
        MainScreenRenderer.save(image, Path.of("build", "preview", "main-screen.png"))

        assertEquals(MainScreenRenderer.WIDTH to MainScreenRenderer.HEIGHT, image.width to image.height)
        val pixels = requireNotNull(image.peekPixels()).buffer.bytes
        assertTrue(pixels.toSet().size > 2, "何も描かれていない")
    }
}
