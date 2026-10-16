package to.sava.legirss

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuBarScope
import androidx.compose.ui.window.MenuScope
import to.sava.legirss.model.ArticleState
import to.sava.legirss.ui.CopyContent
import to.sava.legirss.ui.MainActions
import to.sava.legirss.ui.MainUiState
import to.sava.legirss.ui.NodeSelection
import to.sava.legirss.ui.ScreenLayout
import to.sava.legirss.ui.StateFilter
import to.sava.legirss.ui.pageBack
import to.sava.legirss.ui.readNextPage

private const val FILE_MENU = "ファイル(F)"
private const val IMPORT_OPML = "OPML の読み込み(I)…"
private const val EXPORT_OPML = "OPML の書き出し(E)…"
private const val SETTINGS = "設定(S)…"
private const val CLOSE_WINDOW = "ウィンドウを閉じる(C)"
private const val EXIT = "終了(X)"

private const val CHANNEL_MENU = "チャンネル(C)"
private const val UPDATE_ALL = "全て更新(U)"
private const val UPDATE_SELECTED = "選んでいるものを更新(S)"
private const val CANCEL_UPDATE = "更新を中止(U)"
private const val ADD_CHANNEL = "チャンネルの追加(A)…"
private const val ADD_GROUP = "グループの追加(G)…"

private const val ARTICLE_MENU = "記事(A)"
private const val READ_NEXT = "次を読む(N)"
private const val PAGE_BACK = "本文を前へ戻す(P)"
private const val OPEN_IN_BROWSER = "ブラウザで開く(O)"
private const val TOGGLE_STATE = "既読と未読を切り替える(T)"
private const val MARK_UNREAD = "未読にする(U)"
private const val MARK_READ = "既読にする(R)"
private const val COPY_URL = "URL をコピー(L)"
private const val COPY_TITLE = "タイトルをコピー(C)"
private const val COPY_TITLE_AND_URL = "タイトルと URL をコピー(B)"
private const val DELETE_ARTICLE = "削除(D)"

private const val VIEW_MENU = "表示(V)"
private const val CYCLE_FILTER = "表示する記事の切り替え(K)"
private const val COLUMNS_MENU = "一覧の列(L)"
private const val RELOAD_LIST = "一覧を並べ直す(R)"

private const val HELP_MENU = "ヘルプ(H)"
private const val CHECK_APP_UPDATE = "アプリの更新を確認(U)"

// Esc はメニューのショートカットに届かず、ウィンドウのキー処理で受ける。ここでは項目にキーを示すために付ける。
private val CLOSE_WINDOW_KEY = KeyShortcut(Key.Escape)
private val UPDATE_ALL_KEY = KeyShortcut(Key.F2)
private val UPDATE_SELECTED_KEY = KeyShortcut(Key.F2, alt = true)
private val ADD_CHANNEL_KEY = KeyShortcut(Key.F3)
private val READ_NEXT_KEY = KeyShortcut(Key.Spacebar)
private val PAGE_BACK_KEY = KeyShortcut(Key.Spacebar, shift = true)
private val OPEN_IN_BROWSER_KEY = KeyShortcut(Key.Enter)
private val TOGGLE_STATE_KEY = KeyShortcut(Key.DirectionRight)
private val COPY_URL_KEY = KeyShortcut(Key.L, ctrl = true)
private val DELETE_ARTICLE_KEY = KeyShortcut(Key.Delete)
private val CYCLE_FILTER_KEY = KeyShortcut(Key.K, ctrl = true)
private val RELOAD_LIST_KEY = KeyShortcut(Key.F5)

/** 名前の末尾の「(X)」から、アクセスキーの文字を取り出す。 */
private val MNEMONIC_PATTERN = Regex("""\((\p{Alnum})\)""")

private fun mnemonicOf(label: String): Char? = MNEMONIC_PATTERN.find(label)?.groupValues?.get(1)?.single()

/**
 * メインウィンドウのメニューバー。項目のショートカットが、そのままウィンドウのキー操作になる。
 *
 * ダイアログを出している間は全ての項目を無効にし、ショートカットも働かせない。
 *
 * @param articleScroll 記事の表示領域のスクロール位置。キー操作で本文を送るために、画面と共有する。
 * @param layout 一覧の列の並び。出す列を選ぶ項目に使う。
 * @param canCheckAppUpdate アプリの更新を確かめられるか。インストールしたアプリから起動したときだけ true。
 */
@Composable
fun FrameWindowScope.AppMenuBar(
    state: MainUiState,
    actions: MainActions,
    articleScroll: ScrollState,
    layout: ScreenLayout,
    onLayoutChange: (ScreenLayout) -> Unit,
    onCloseWindow: () -> Unit,
    onExit: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    canCheckAppUpdate: Boolean,
) {
    val enabled = state.dialog == null
    MenuBar {
        TopMenu(FILE_MENU) {
            Command(IMPORT_OPML, enabled, onClick = actions::importOpml)
            Command(EXPORT_OPML, enabled) { actions.exportOpml(NodeSelection.All) }
            Separator()
            Command(SETTINGS, enabled, onClick = actions::openSettings)
            Separator()
            Command(CLOSE_WINDOW, enabled, CLOSE_WINDOW_KEY, onCloseWindow)
            Command(EXIT, enabled, onClick = onExit)
        }
        TopMenu(CHANNEL_MENU) {
            if (state.updateProgress == null) {
                Command(UPDATE_ALL, enabled, UPDATE_ALL_KEY, actions::updateAll)
                Command(UPDATE_SELECTED, enabled, UPDATE_SELECTED_KEY, actions::updateSelected)
            } else {
                Command(CANCEL_UPDATE, enabled, UPDATE_ALL_KEY, actions::updateAll)
            }
            Separator()
            Command(ADD_CHANNEL, enabled, ADD_CHANNEL_KEY, actions::openAddChannel)
            Command(ADD_GROUP, enabled, onClick = actions::openAddGroup)
        }
        TopMenu(ARTICLE_MENU) { ArticleCommands(state, actions, articleScroll, enabled) }
        TopMenu(VIEW_MENU) {
            StateFilter.entries.forEach { filter ->
                RadioButtonItem(filter.label, selected = state.filter == filter, enabled = enabled) { actions.setFilter(filter) }
            }
            Command(CYCLE_FILTER, enabled, CYCLE_FILTER_KEY, actions::cycleFilter)
            Separator()
            Menu(COLUMNS_MENU, mnemonic = mnemonicOf(COLUMNS_MENU), enabled = enabled) {
                layout.columnOrder.filter { it.hideable }.forEach { column ->
                    val visible = column !in layout.hiddenColumns
                    CheckboxItem(column.title, checked = visible, enabled = enabled) {
                        onLayoutChange(layout.withColumnVisible(column, !visible))
                    }
                }
            }
            Command(RELOAD_LIST, enabled, RELOAD_LIST_KEY, actions::reloadList)
        }
        TopMenu(HELP_MENU) {
            Command(CHECK_APP_UPDATE, enabled && canCheckAppUpdate, onClick = onCheckAppUpdate)
        }
    }
}

@Composable
private fun MenuScope.ArticleCommands(state: MainUiState, actions: MainActions, articleScroll: ScrollState, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    Command(READ_NEXT, enabled, READ_NEXT_KEY) { actions.readNextPage(state.selectedArticle != null, articleScroll, scope) }
    Command(PAGE_BACK, enabled, PAGE_BACK_KEY) { pageBack(articleScroll, scope) }
    Command(OPEN_IN_BROWSER, enabled, OPEN_IN_BROWSER_KEY, actions::openSelectedArticle)
    Separator()
    Command(TOGGLE_STATE, enabled, TOGGLE_STATE_KEY, actions::toggleSelectedState)
    Command(MARK_UNREAD, enabled) { actions.setSelectedState(ArticleState.UNREAD) }
    Command(MARK_READ, enabled) { actions.setSelectedState(ArticleState.READ) }
    Separator()
    Command(COPY_URL, enabled, COPY_URL_KEY) { actions.copySelected(CopyContent.URL) }
    Command(COPY_TITLE, enabled) { actions.copySelected(CopyContent.TITLE) }
    Command(COPY_TITLE_AND_URL, enabled) { actions.copySelected(CopyContent.TITLE_AND_URL) }
    Separator()
    Command(DELETE_ARTICLE, enabled, DELETE_ARTICLE_KEY, actions::deleteSelectedArticles)
}

@Composable
private fun MenuBarScope.TopMenu(label: String, content: @Composable MenuScope.() -> Unit) {
    Menu(label, mnemonic = mnemonicOf(label), content = content)
}

/** アクセスキーを名前から取り出して付けた、メニューの項目。 */
@Composable
private fun MenuScope.Command(label: String, enabled: Boolean, shortcut: KeyShortcut? = null, onClick: () -> Unit) {
    Item(label, enabled = enabled, mnemonic = mnemonicOf(label), shortcut = shortcut, onClick = onClick)
}
