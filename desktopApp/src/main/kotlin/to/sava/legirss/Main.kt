package to.sava.legirss

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
import to.sava.legirss.ui.MainScreen
import to.sava.legirss.ui.MainViewModel
import to.sava.legirss.ui.theme.LegirssStyle
import to.sava.legirss.ui.theme.LocalLegirssStyle
import java.nio.file.Path

private const val APP_NAME = "legirss"
private const val UI_FONT_NAME = "Yu Gothic UI"
private const val DATA_DIR_PROPERTY = "legirss.dataDir"
private val DEFAULT_WINDOW_SIZE = DpSize(1100.dp, 720.dp)

fun main() {
    val store = LegirssStore(DatabaseFactory.open(dataDir()), Dispatchers.IO)
    val style = LegirssStyle(fontFamily = uiFontFamily())
    application {
        val scope = rememberCoroutineScope()
        val viewModel = remember { MainViewModel(store, scope).also(MainViewModel::load) }
        Window(
            onCloseRequest = ::exitApplication,
            title = APP_NAME,
            state = rememberWindowState(size = DEFAULT_WINDOW_SIZE),
        ) {
            CompositionLocalProvider(LocalLegirssStyle provides style) {
                MainScreen(viewModel)
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
