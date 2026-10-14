package to.sava.legirss

import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities

/**
 * ウィンドウの中でキーを受け取る部品を覚えておき、求められたときにフォーカスをそこへ移す。
 *
 * キーの操作は、ウィンドウの中の部品がフォーカスを持っているときだけ画面へ届く。ウィンドウ自身に
 * フォーカスを要求すると、フォーカスはウィンドウに留まって中の部品へは移らないので、覚えた部品へ要求する。
 */
internal class WindowContentFocus(private val window: Window) : PropertyChangeListener {
    private var content: Component? = null

    /** フォーカスの移り変わりを見はじめる。 */
    fun install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(FOCUS_OWNER_PROPERTY, this)
    }

    /** フォーカスの移り変わりを見るのをやめる。 */
    fun remove() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener(FOCUS_OWNER_PROPERTY, this)
    }

    override fun propertyChange(event: PropertyChangeEvent) {
        val owner = event.newValue as? Component ?: return
        if (owner !is Window && SwingUtilities.getWindowAncestor(owner) === window) content = owner
    }

    /**
     * 覚えている部品へフォーカスを要求する。
     *
     * 中の部品がまだ一度もフォーカスを持っていなければ、何もしない。最初に表示するときのフォーカスは、
     * 画面の側が中の部品へ置く。
     */
    fun request() {
        content?.requestFocus()
    }

    private companion object {
        const val FOCUS_OWNER_PROPERTY = "permanentFocusOwner"
    }
}
