package to.sava.legirss

import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.awt.ComposeWindow
import to.sava.legirss.ui.DialogState
import to.sava.legirss.ui.MainActions
import to.sava.legirss.ui.MainUiState
import to.sava.legirss.ui.ScreenLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.MenuElement
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * メインウィンドウのメニューバーを、画面に出さないウィンドウに組み込んで確かめる。
 *
 * キー操作はメニューの項目のショートカットとして受けるので、項目のショートカットと、項目を選んだときに呼ばれる操作を見る。
 */
class AppMenuBarTest {
    /** 呼ばれた操作の名前。ウィンドウを閉じる操作は [CLOSE_WINDOW_CALL] として記録する。 */
    private val calls = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(MainActions::class.java.classLoader, arrayOf(MainActions::class.java)) { _, method, _ ->
        calls.add(method.name)
        null
    } as MainActions

    private var window: ComposeWindow? = null

    @AfterTest
    fun disposeWindow() {
        SwingUtilities.invokeAndWait { window?.dispose() }
    }

    /** メニューバーを組み込んだウィンドウを、画面に出さずに作り、項目が組み上がるまで待つ。 */
    private fun buildMenu(state: MainUiState) {
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                setContent {
                    AppMenuBar(
                        state = state,
                        actions = actions,
                        articleScroll = rememberScrollState(),
                        layout = ScreenLayout(),
                        onLayoutChange = {},
                        onCloseWindow = { calls.add(CLOSE_WINDOW_CALL) },
                        onExit = {},
                        onCheckAppUpdate = {},
                        canCheckAppUpdate = false,
                    )
                }
                pack()
            }
        }
        val deadline = System.currentTimeMillis() + BUILD_TIMEOUT_MILLIS
        while (menuItems().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "メニューの項目が組み上がらない" }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun menuItems(): List<JMenuItem> {
        var items = emptyList<JMenuItem>()
        SwingUtilities.invokeAndWait { items = window?.jMenuBar?.let(::itemsUnder).orEmpty() }
        return items
    }

    private fun itemsUnder(element: MenuElement): List<JMenuItem> =
        element.subElements.flatMap { child -> listOfNotNull(child as? JMenuItem) + itemsUnder(child) }

    /** [keyStroke] をショートカットにした項目を選び、そのとき呼ばれた操作を返す。 */
    private fun callsOn(keyStroke: KeyStroke): List<String> {
        calls.clear()
        val item = menuItems().single { it.accelerator == keyStroke }
        SwingUtilities.invokeAndWait { item.doClick() }
        return calls.toList()
    }

    /** 各キーが、決めた操作の項目のショートカットになっている。記事を選んでいなければ、Space は次の未読へ移る。 */
    @Test
    fun assignsKeysToOperations() {
        buildMenu(MainUiState())
        val expected = listOf(
            key(KeyEvent.VK_SPACE) to "readOn",
            key(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK) to "selectAbove",
            key(KeyEvent.VK_ENTER) to "openSelectedArticle",
            key(KeyEvent.VK_RIGHT) to "toggleSelectedState",
            key(KeyEvent.VK_DELETE) to "deleteSelectedArticles",
            key(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK) to "copySelected",
            key(KeyEvent.VK_F2) to "updateAll",
            key(KeyEvent.VK_F2, InputEvent.ALT_DOWN_MASK) to "updateSelected",
            key(KeyEvent.VK_F3) to "openAddChannel",
            key(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK) to "cycleFilter",
            key(KeyEvent.VK_F5) to "reloadList",
            key(KeyEvent.VK_ESCAPE) to CLOSE_WINDOW_CALL,
        )

        val actual = expected.map { (keyStroke, _) -> keyStroke to callsOn(keyStroke) }

        assertEquals(expected.map { (keyStroke, call) -> keyStroke to listOf(call) }, actual)
    }

    /** ダイアログを出している間は、メニューバーに並ぶメニューの中の項目が全て無効になり、ショートカットも働かない。 */
    @Test
    fun disablesAllItemsWhileDialogIsShown() {
        buildMenu(MainUiState(dialog = DialogState.Settings))

        val enabledItems = menuItems().filter { it.parent !is JMenuBar && it.isEnabled }.map { it.text }

        assertEquals(emptyList(), enabledItems)
    }

    private fun key(keyCode: Int, modifiers: Int = 0): KeyStroke = KeyStroke.getKeyStroke(keyCode, modifiers)

    private companion object {
        const val CLOSE_WINDOW_CALL = "closeWindow"
        const val BUILD_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 20L
    }
}
