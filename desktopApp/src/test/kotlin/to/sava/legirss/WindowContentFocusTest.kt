package to.sava.legirss

import java.awt.Component
import java.beans.PropertyChangeEvent
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowContentFocusTest {
    private val window = JFrame()
    private val otherWindow = JFrame()
    private val content = CountingPanel().also(window::add)
    private val focus = WindowContentFocus(window)

    @AfterTest
    fun disposeWindows() {
        window.dispose()
        otherWindow.dispose()
    }

    /** ウィンドウの中の部品がフォーカスを持ったあとは、フォーカスの要求がその部品へ届く。 */
    @Test
    fun requestsFocusOnContentThatHadFocus() {
        focus.focusMovedTo(content)

        focus.request()

        assertEquals(1, content.focusRequests)
    }

    /** ウィンドウ自身や、ほかのウィンドウの部品がフォーカスを持っても、要求する先は中の部品のまま変わらない。 */
    @Test
    fun keepsContentWhenFocusMovesToWindowItselfOrElsewhere() {
        val elsewhere = CountingPanel().also(otherWindow::add)
        focus.focusMovedTo(content)
        focus.focusMovedTo(window)
        focus.focusMovedTo(elsewhere)
        focus.focusMovedTo(null)

        focus.request()

        assertEquals(1, content.focusRequests)
        assertEquals(0, elsewhere.focusRequests)
    }

    /** 中の部品がまだフォーカスを持ったことがなければ、どこへもフォーカスを要求しない。 */
    @Test
    fun requestsNothingBeforeContentHadFocus() {
        focus.focusMovedTo(window)

        focus.request()

        assertEquals(0, content.focusRequests)
    }

    private fun WindowContentFocus.focusMovedTo(owner: Component?) {
        propertyChange(PropertyChangeEvent(this, FOCUS_OWNER_PROPERTY, null, owner))
    }

    /** フォーカスを要求された回数を数える部品。 */
    private class CountingPanel : JPanel() {
        var focusRequests = 0
            private set

        override fun requestFocus() {
            focusRequests += 1
        }
    }

    private companion object {
        const val FOCUS_OWNER_PROPERTY = "permanentFocusOwner"
    }
}
