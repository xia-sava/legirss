package to.sava.legirss

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import to.sava.legirss.ui.MainUiState
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
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.UnsupportedLookAndFeelException

private const val APP_NAME = "legirss"
private const val UI_FONT_NAME = "Yu Gothic UI"
private const val DATA_DIR_PROPERTY = "legirss.dataDir"
private const val AUTOMATIC_UPDATE_INTERVAL_MILLIS = 60 * 60 * 1000L
private val DEFAULT_WINDOW_SIZE = DpSize(1100.dp, 720.dp)

fun main(args: Array<String>) {
    val dataDir = dataDir()
    useSystemLookAndFeel()

    // ウィンドウを出しているか。すでに起動しているところへもう一度起動されたときにも true にする。
    var windowVisible by mutableStateOf(StartupRegistration.START_IN_TRAY_OPTION !in args)
    SingleInstance.acquire(dataDir) { SwingUtilities.invokeLater { windowVisible = true } } ?: return

    val store = LegirssStore(DatabaseFactory.open(dataDir), Dispatchers.IO)
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
        val state by viewModel.state.collectAsState()
        val windowState = rememberWindowState(size = DEFAULT_WINDOW_SIZE)

        val tray = remember {
            TrayController(
                onToggleWindow = { windowVisible = !windowVisible },
                onShowWindow = { windowVisible = true },
                onUpdate = viewModel::updateAll,
                onExit = ::exitApplication,
                startup = StartupRegistration.forCurrentProcess(),
            ).also(TrayController::install)
        }
        DisposableEffect(Unit) { onDispose(tray::remove) }
        LaunchedEffect(state.updateProgress != null, state.totalCount.unread) {
            tray.update(trayStateOf(state), "$APP_NAME  未読 ${state.totalCount.unread} 件")
        }

        LaunchedEffect(windowVisible) { viewModel.setWindowVisible(windowVisible) }
        LaunchedEffect(Unit) { viewModel.runAutomaticUpdates(AUTOMATIC_UPDATE_INTERVAL_MILLIS) }

        // 最小化はタスクトレイへしまう操作として扱う。
        LaunchedEffect(windowState.isMinimized) {
            if (windowState.isMinimized) {
                windowState.isMinimized = false
                windowVisible = false
            }
        }

        Window(
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            title = APP_NAME,
            state = windowState,
            onKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    windowVisible = false
                    true
                } else {
                    viewModel.handleKey(event, state.selectedArticle != null, articleScroll, scope)
                }
            },
        ) {
            // しまっていたウィンドウを出したときは、ほかのウィンドウの前へ持ってくる。
            LaunchedEffect(windowVisible) {
                if (windowVisible) {
                    window.toFront()
                    window.requestFocus()
                }
            }
            CompositionLocalProvider(LocalLegirssStyle provides style) {
                MainScreen(state, viewModel, articleScroll)
            }
        }
    }
}

private fun trayStateOf(state: MainUiState): TrayState = when {
    state.updateProgress != null -> TrayState.UPDATING
    state.totalCount.unread > 0 -> TrayState.UNREAD
    else -> TrayState.NORMAL
}

/** Swing で出す部品（タスクトレイのメニュー）を、OS の見た目に合わせる。 */
internal fun useSystemLookAndFeel() {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (exception: ReflectiveOperationException) {
        System.err.println("OS の見た目を適用できません: ${exception.message}")
    } catch (exception: UnsupportedLookAndFeelException) {
        System.err.println("OS の見た目を適用できません: ${exception.message}")
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
