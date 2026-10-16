package to.sava.legirss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import to.sava.legirss.feed.FeedCandidate
import to.sava.legirss.html.HtmlBlocks
import to.sava.legirss.model.ArticleCount
import to.sava.legirss.model.ArticleRow
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.ChannelInfo
import to.sava.legirss.model.NodeKind
import to.sava.legirss.model.TreeNode
import to.sava.legirss.ui.component.DialogFrame
import to.sava.legirss.ui.component.LocalDialogFrame
import to.sava.legirss.ui.theme.LegirssStyle
import to.sava.legirss.ui.theme.LocalLegirssStyle
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 何もしない操作。描画だけを確かめるときに渡す。どの操作も値を返さないので、呼び出しを受けて捨てる。 */
private val NoActions: MainActions = Proxy.newProxyInstance(
    MainActions::class.java.classLoader,
    arrayOf(MainActions::class.java),
) { _, _, _ -> null } as MainActions

/**
 * ダイアログを、画面の中央に重ねて描く枠。題名を OS のタイトルバーの代わりに上端へ描く。
 *
 * アプリでは OS のダイアログに出すが、画面に出さない描画ではウィンドウを作れないため、これで代える。
 */
private object OverlayDialogFrame : DialogFrame {
    private val TITLE_BAR_HEIGHT = 24.dp
    private val TITLE_PADDING = 8.dp
    private val BORDER_WIDTH = 1.dp
    private val SCRIM_COLOR = Color(0x33000000)

    @Composable
    override fun Show(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
        val style = LocalLegirssStyle.current
        Box(Modifier.fillMaxSize().background(SCRIM_COLOR), contentAlignment = Alignment.Center) {
            Column(Modifier.width(IntrinsicSize.Max).border(BORDER_WIDTH, style.colors.border).background(style.colors.background)) {
                Box(Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).padding(horizontal = TITLE_PADDING), contentAlignment = Alignment.CenterStart) {
                    BasicText(title, style = style.textStyle())
                }
                content()
            }
        }
    }
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

    fun render(state: MainUiState, statusNotice: StatusNotice? = null): Image =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(LocalLegirssStyle provides style, LocalDialogFrame provides OverlayDialogFrame) {
                MainScreen(state, NoActions, rememberScrollState(), ScreenLayout(), onLayoutChange = {}, statusNotice = statusNotice)
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
            fetchedAt = id * 3_600_000,
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

    /** 手前に出すダイアログを持つ状態を、ダイアログごと描画できる。 */
    @Test
    fun rendersDialogOverScreen() {
        val dialog = DialogState.AddChannel(
            message = "登録するフィードを選んでください",
            candidates = listOf(
                FeedCandidate("https://example.com/rss.xml", "サンプル通信 (RSS)"),
                FeedCandidate("https://example.com/atom.xml", null),
            ),
        )

        val image = MainScreenRenderer.render(MainUiState(dialog = dialog))
        MainScreenRenderer.save(image, Path.of("build", "preview", "dialog.png"))

        assertEquals(MainScreenRenderer.WIDTH to MainScreenRenderer.HEIGHT, image.width to image.height)
    }

    /** ステータスバーの右端の通知を、押せるものと押せないものの両方で描画できる。 */
    @Test
    fun rendersStatusNotice() {
        val notices = mapOf(
            "status-notice-button.png" to StatusNotice("新しい版 1.2.3 に更新して再起動", onClick = {}),
            "status-notice-text.png" to StatusNotice("最新の版です", onClick = null),
        )

        notices.forEach { (fileName, notice) ->
            val image = MainScreenRenderer.render(MainUiState(), notice)
            MainScreenRenderer.save(image, Path.of("build", "preview", fileName))

            assertEquals(MainScreenRenderer.WIDTH to MainScreenRenderer.HEIGHT, image.width to image.height)
        }
    }

    /** 設定のダイアログを、いまの設定を入力欄に入れた状態で描画できる。 */
    @Test
    fun rendersSettingsDialog() {
        val image = MainScreenRenderer.render(MainUiState(dialog = DialogState.Settings))
        MainScreenRenderer.save(image, Path.of("build", "preview", "settings.png"))

        assertEquals(MainScreenRenderer.WIDTH to MainScreenRenderer.HEIGHT, image.width to image.height)
    }
}
