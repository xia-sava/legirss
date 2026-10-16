package to.sava.legirss

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import to.sava.legirss.ui.MainActions
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowKeysTest {
    /** 呼ばれた操作の名前。ウィンドウをしまう操作は [CLOSE_WINDOW_CALL] として記録する。 */
    private val calls = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(MainActions::class.java.classLoader, arrayOf(MainActions::class.java)) { _, method, _ ->
        calls.add(method.name)
        null
    } as MainActions

    /**
     * [key] を押したときに処理されたかと、呼ばれた操作を返す。
     *
     * キーのイベントを作る公開の関数が無いので、Compose の内部向けの関数で作る。
     */
    @OptIn(InternalComposeUiApi::class)
    private fun press(key: Key, dialogShown: Boolean): Pair<Boolean, List<String>> {
        calls.clear()
        val handled = handleWindowKey(KeyEvent(key, KeyEventType.KeyDown), dialogShown, actions) { calls.add(CLOSE_WINDOW_CALL) }
        return handled to calls.toList()
    }

    /** ダイアログを出していなければ、Esc でウィンドウをしまい、← で記事の未読と既読を切り替える。ほかのキーは処理しない。 */
    @Test
    fun handlesKeysWithoutDialog() {
        val expected = listOf(
            Key.Escape to (true to listOf(CLOSE_WINDOW_CALL)),
            Key.DirectionLeft to (true to listOf("toggleSelectedState")),
            Key.A to (false to emptyList()),
        )

        val actual = expected.map { (key, _) -> key to press(key, dialogShown = false) }

        assertEquals(expected, actual)
    }

    /** ダイアログを出している間は、Esc も ← も処理せず、何の操作も呼ばない。 */
    @Test
    fun ignoresKeysWhileDialogIsShown() {
        val keys = listOf(Key.Escape, Key.DirectionLeft)

        val actual = keys.map { key -> key to press(key, dialogShown = true) }

        assertEquals(keys.map { it to (false to emptyList<String>()) }, actual)
    }

    private companion object {
        const val CLOSE_WINDOW_CALL = "closeWindow"
    }
}
