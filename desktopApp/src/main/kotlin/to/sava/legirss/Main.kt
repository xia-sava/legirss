package to.sava.legirss

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import to.sava.legirss.data.DatabaseFactory
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.feed.FeedHttp
import to.sava.legirss.ui.MainScreen
import to.sava.legirss.ui.MainViewModel
import to.sava.legirss.ui.handleKey
import to.sava.legirss.ui.theme.LegirssStyle
import to.sava.legirss.ui.theme.LocalLegirssStyle
import to.sava.legirss.update.FeedUpdater
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

private const val APP_NAME = "legirss"
private const val UI_FONT_NAME = "Yu Gothic UI"
private const val DATA_DIR_PROPERTY = "legirss.dataDir"
private val DEFAULT_WINDOW_SIZE = DpSize(1100.dp, 720.dp)

fun main() {
    val store = LegirssStore(DatabaseFactory.open(dataDir()), Dispatchers.IO)
    val updater = FeedUpdater(
        store = store,
        fetcher = FeedHttp.createFetcher(),
        parseDispatcher = Dispatchers.Default,
        now = System::currentTimeMillis,
    )
    val style = LegirssStyle(fontFamily = uiFontFamily())
    application {
        val scope = rememberCoroutineScope()
        val articleScroll = rememberScrollState()
        val viewModel = remember {
            MainViewModel(
                store = store,
                updater = updater,
                scope = scope,
                parseDispatcher = Dispatchers.Default,
                now = System::currentTimeMillis,
                openUrl = ::openInBrowser,
            ).also(MainViewModel::load)
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = APP_NAME,
            state = rememberWindowState(size = DEFAULT_WINDOW_SIZE),
            onKeyEvent = { event ->
                viewModel.handleKey(event, viewModel.state.value.selectedArticle != null, articleScroll, scope)
            },
        ) {
            CompositionLocalProvider(LocalLegirssStyle provides style) {
                MainScreen(viewModel, articleScroll)
            }
        }
    }
}

/** Windows の UI 用フォントを名前で引く。 */
@OptIn(ExperimentalTextApi::class)
private fun uiFontFamily(): FontFamily =
    FontFamily(SystemFont(UI_FONT_NAME), SystemFont(UI_FONT_NAME, FontWeight.Bold))

/** データの置き場所。システムプロパティで指定されていなければ `%APPDATA%\legirss`。 */
private fun dataDir(): Path =
    System.getProperty(DATA_DIR_PROPERTY)?.let(Path::of)
        ?: System.getenv("APPDATA")?.let { Path.of(it, APP_NAME) }
        ?: Path.of(System.getProperty("user.home"), ".$APP_NAME")

/** URL を既定のブラウザで開く。開けなかった場合は理由を標準エラーに出す。 */
private fun openInBrowser(url: String) {
    try {
        Desktop.getDesktop().browse(URI(url))
    } catch (exception: URISyntaxException) {
        System.err.println("URL として扱えません: $url (${exception.message})")
    } catch (exception: IOException) {
        System.err.println("ブラウザで開けません: $url (${exception.message})")
    }
}
