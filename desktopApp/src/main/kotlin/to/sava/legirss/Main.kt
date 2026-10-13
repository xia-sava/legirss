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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import to.sava.legirss.data.DatabaseBackup
import to.sava.legirss.data.DatabaseFactory
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.feed.FeedHttp
import to.sava.legirss.feed.FeedLocator
import to.sava.legirss.model.AppSettings
import to.sava.legirss.ui.MainScreen
import to.sava.legirss.ui.MainUiState
import to.sava.legirss.ui.MainViewModel
import to.sava.legirss.ui.ScreenLayout
import to.sava.legirss.ui.ToolbarNotice
import to.sava.legirss.ui.handleKey
import to.sava.legirss.ui.theme.LegirssStyle
import to.sava.legirss.ui.theme.LocalLegirssStyle
import to.sava.legirss.update.AppUpdater
import to.sava.legirss.update.AppVersion
import to.sava.legirss.update.FeedUpdater
import to.sava.legirss.update.UpdateChecker
import to.sava.legirss.update.UpdateHttp
import to.sava.legirss.update.UpdateInstaller
import to.sava.legirss.update.UpdateManifest
import to.sava.legirss.update.UpdateState
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Path
import java.sql.SQLException
import java.time.LocalDate
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.UnsupportedLookAndFeelException
import kotlin.math.roundToInt

private const val APP_NAME = "legirss"
private const val UI_FONT_NAME = "Yu Gothic UI"
private const val DATA_DIR_PROPERTY = "legirss.dataDir"
private const val BACKUP_CHECK_INTERVAL_MILLIS = 60 * 60 * 1000L
private const val LAYOUT_SAVE_DELAY_MILLIS = 500L
private const val WINDOW_ICON_SIZE = 64

// 一時ファイルを置く場所を持つシステムプロパティ。
private const val TEMP_DIR_PROPERTY = "java.io.tmpdir"
private const val UPDATE_CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

// 自分の版を読めなかったときに使う版。どの配布中の版よりも古いものとして扱う。
private val UNKNOWN_VERSION = requireNotNull(AppVersion.parse("0"))
private val DEFAULT_WINDOW_SIZE = DpSize(1100.dp, 720.dp)

fun main(args: Array<String>) {
    val dataDir = dataDir()
    useSystemLookAndFeel()

    // ウィンドウを出しているか。すでに起動しているところへもう一度起動されたときにも true にする。
    var windowVisible by mutableStateOf(StartupRegistration.START_IN_TRAY_OPTION !in args)
    SingleInstance.acquire(dataDir) { SwingUtilities.invokeLater { windowVisible = true } } ?: return

    AppLog.writeTo(dataDir)
    Thread.setDefaultUncaughtExceptionHandler { _, exception -> AppLog.error("処理されなかった例外", exception) }

    val store = LegirssStore(DatabaseFactory.open(dataDir), Dispatchers.IO)
    val fetcher = FeedHttp.createFetcher()
    val updater = FeedUpdater(
        store = store,
        fetcher = fetcher,
        workDispatcher = Dispatchers.Default,
        now = System::currentTimeMillis,
        reportError = AppLog::error,
    )
    val backup = DatabaseBackup(dataDir, store, LocalDate::now)
    val browser = BrowserLauncher()
    // ウィンドウのタイトルに出す、アプリの名前と版。版を読めなければ名前だけにする。
    val appTitle = listOfNotNull(APP_NAME, AppVersion.current()?.toString()).joinToString(" ")
    val fontFamily = uiFontFamily()
    val windowIcon = BitmapPainter(TrayController.drawIcon(TrayState.UNREAD, WINDOW_ICON_SIZE).toComposeImageBitmap())
    // 最初の描画から保存した配置で出すため、設定は画面を組み立てる前に読み終える。
    val settings = runBlocking { store.loadSettings() }
    val savedBounds = WindowBounds.fromSettings(settings)?.takeIf { it.isReachableOn(screenBounds()) }
    val savedMaximized = settings[WindowBounds.MAXIMIZED_KEY] == WindowBounds.MAXIMIZED_ON

    application {
        val scope = rememberCoroutineScope()
        val articleScroll = rememberScrollState()
        val viewModel = remember {
            MainViewModel(
                store = store,
                updater = updater,
                locator = FeedLocator(fetcher, Dispatchers.Default),
                parentScope = scope,
                parseDispatcher = Dispatchers.Default,
                now = System::currentTimeMillis,
                openUrl = browser::open,
                copyText = ::copyToClipboard,
                opmlFiles = DesktopOpmlFiles(Dispatchers.IO),
                reportError = AppLog::error,
                initialSettings = AppSettings.fromSettings(settings),
            ).also(MainViewModel::load)
        }
        val state by viewModel.state.collectAsState()
        val style = remember(state.settings) {
            LegirssStyle(
                fontFamily = fontFamily,
                treeFontSize = state.settings.treeFontSize.sp,
                listFontSize = state.settings.listFontSize.sp,
                articleFontSize = state.settings.articleFontSize.sp,
            )
        }
        val windowState = rememberWindowState(
            placement = if (savedMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = savedBounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = savedBounds?.let { DpSize(it.width.dp, it.height.dp) } ?: DEFAULT_WINDOW_SIZE,
        )
        var layout by remember { mutableStateOf(ScreenLayout.fromSettings(settings)) }

        // 動かしている最中は保存せず、動きが止まってから保存する。
        LaunchedEffect(Unit) {
            snapshotFlow { layout.toSettings() + windowSettingsOf(windowState) }
                .drop(1)
                .collectLatest { changed ->
                    delay(LAYOUT_SAVE_DELAY_MILLIS)
                    viewModel.saveSettings(changed)
                }
        }

        val tray = remember {
            TrayController(
                onToggleWindow = { windowVisible = !windowVisible },
                onShowWindow = { windowVisible = true },
                onUpdate = viewModel::updateAll,
                onOpenSettings = {
                    windowVisible = true
                    viewModel.openSettings()
                },
                onExit = ::exitApplication,
                startup = StartupRegistration.forCurrentProcess(),
            ).also(TrayController::install)
        }
        DisposableEffect(Unit) { onDispose(tray::remove) }
        LaunchedEffect(state.updateProgress != null, state.totalCount.unread) {
            tray.update(
                state = trayStateOf(state),
                updating = state.updateProgress != null,
                tooltip = "$APP_NAME  未読 ${state.totalCount.unread} 件",
            )
        }

        LaunchedEffect(windowVisible) { viewModel.setWindowVisible(windowVisible) }
        LaunchedEffect(Unit) { viewModel.runAutomaticUpdates() }
        LaunchedEffect(Unit) { runDailyBackups(backup) }

        val updateHttp = remember { UpdateHttp(Dispatchers.IO) }
        val installer = remember {
            UpdateInstaller(
                launcherPath = StartupRegistration.launcherPath(),
                logDir = dataDir,
                downloadRoot = Path.of(System.getProperty(TEMP_DIR_PROPERTY)),
                download = updateHttp::download,
            )
        }
        // 前の版から更新した直後なら、そのときに取得した配布物が残っているので片付ける。
        LaunchedEffect(Unit) { removeOldDownloads(installer) }
        val appUpdater = remember {
            AppUpdater(
                checker = UpdateChecker(AppVersion.current() ?: UNKNOWN_VERSION, UpdateManifest.PUBLIC_KEY, updateHttp::fetch),
                canInstall = installer.isSupported,
                download = installer::downloadVerified,
                launch = installer::launch,
                scope = scope,
                reportError = AppLog::error,
                onReadyToExit = ::exitApplication,
            )
        }
        val updateState by appUpdater.state.collectAsState()
        LaunchedEffect(Unit) { appUpdater.runPeriodicChecks(UPDATE_CHECK_INTERVAL_MILLIS) }

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
            title = "${state.selectedNodeName} - $appTitle",
            icon = windowIcon,
            state = windowState,
            onKeyEvent = { event ->
                when {
                    // ダイアログを手前に出している間は、後ろの画面のキー操作を受け付けない。
                    state.dialog != null -> false
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                        windowVisible = false
                        true
                    }
                    else -> viewModel.handleKey(event, state.selectedArticle != null, articleScroll, scope)
                }
            },
        ) {
            DisposableEffect(window) {
                browser.ownerWindow = window
                val focusLog = WindowFocusLog(window)
                window.addWindowFocusListener(focusLog)
                onDispose {
                    window.removeWindowFocusListener(focusLog)
                    browser.ownerWindow = null
                }
            }
            // しまっていたウィンドウを出したときは、ほかのウィンドウの前へ持ってくる。
            LaunchedEffect(windowVisible) {
                if (windowVisible) {
                    window.toFront()
                    window.requestFocus()
                }
            }
            CompositionLocalProvider(LocalLegirssStyle provides style) {
                MainScreen(
                    state = state,
                    actions = viewModel,
                    articleScroll = articleScroll,
                    layout = layout,
                    onLayoutChange = { layout = it },
                    toolbarNotice = updateNoticeOf(updateState, onInstall = appUpdater::install),
                )
            }
        }
    }
}

/** 前に取得した配布物を片付ける。片付けられなかった場合は理由を記録し、次に起動したときにもう一度試みる。 */
private suspend fun removeOldDownloads(installer: UpdateInstaller) {
    withContext(Dispatchers.IO) {
        try {
            installer.removeOldDownloads()
        } catch (exception: IOException) {
            AppLog.warn("前に取得した配布物を片付けられません", exception)
        }
    }
}

/** アプリの更新の状況を、ツールバーに出す通知にする。新しい版が無ければ何も出さない。 */
private fun updateNoticeOf(state: UpdateState, onInstall: () -> Unit): ToolbarNotice? = when (state) {
    UpdateState.Idle -> null
    is UpdateState.Available -> ToolbarNotice("新しい版 ${state.release.version} に更新して再起動", onInstall)
    is UpdateState.Installing -> ToolbarNotice("新しい版 ${state.release.version} を取得中…", onClick = null)
    is UpdateState.Failed -> ToolbarNotice("更新できませんでした。もう一度試す", onInstall)
}

private fun trayStateOf(state: MainUiState): TrayState =
    if (state.totalCount.unread > 0) TrayState.UNREAD else TrayState.NORMAL

/** つながっている全ての画面の範囲。 */
private fun screenBounds(): List<Rectangle> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }

/**
 * ウィンドウの配置のうち、保存する設定。
 *
 * 位置と大きさは最大化していないときのものだけを保存し、最大化を解いたときに戻る先を残す。
 * 最小化している間の位置は画面の外を指すので、何も保存しない。
 */
private fun windowSettingsOf(state: WindowState): Map<String, String> {
    val position = state.position
    return when {
        state.isMinimized -> emptyMap()
        state.placement != WindowPlacement.Floating -> mapOf(WindowBounds.MAXIMIZED_KEY to WindowBounds.MAXIMIZED_ON)
        position !is WindowPosition.Absolute || !state.size.isSpecified -> emptyMap()
        else -> WindowBounds(
            x = position.x.value.roundToInt(),
            y = position.y.value.roundToInt(),
            width = state.size.width.value.roundToInt(),
            height = state.size.height.value.roundToInt(),
        ).toSettings() + (WindowBounds.MAXIMIZED_KEY to WindowBounds.MAXIMIZED_OFF)
    }
}

/**
 * その日のバックアップがまだ無ければ作ることを、[BACKUP_CHECK_INTERVAL_MILLIS] ごとに繰り返す。
 *
 * 呼び出したコルーチンが取り消されるまで戻らない。作れなかった場合は、次の機会にもう一度試みる。
 */
private suspend fun runDailyBackups(backup: DatabaseBackup): Nothing {
    while (true) {
        try {
            backup.backUpIfDue()
        } catch (exception: IOException) {
            AppLog.warn("バックアップを作れません", exception)
        } catch (exception: SQLException) {
            AppLog.warn("バックアップを作れません", exception)
        }
        delay(BACKUP_CHECK_INTERVAL_MILLIS)
    }
}

/** Swing で出す部品（タスクトレイのメニュー）を、OS の見た目に合わせる。 */
internal fun useSystemLookAndFeel() {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (exception: ReflectiveOperationException) {
        AppLog.warn("OS の見た目を適用できません", exception)
    } catch (exception: UnsupportedLookAndFeelException) {
        AppLog.warn("OS の見た目を適用できません", exception)
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

/** 文字をクリップボードへ写す。ほかのアプリがクリップボードを使っていて写せなかった場合は理由を記録する。 */
private fun copyToClipboard(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    } catch (exception: IllegalStateException) {
        AppLog.warn("クリップボードへ写せません", exception)
    }
}
