package to.sava.legirss

import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities

/**
 * ウィンドウがフォーカスを得たときと失ったときに、フォーカスの状態を記録する。
 *
 * キーの操作が効かなくなったときに、ウィンドウの中のどの部品がフォーカスを持っていたかを確かめるためのもの。
 */
internal class WindowFocusLog(private val window: Window) : WindowFocusListener {
    // フォーカスを持つ部品は、ウィンドウがフォーカスを得たあとに決まるので、決まるのを待ってから記録する。
    override fun windowGainedFocus(event: WindowEvent) {
        SwingUtilities.invokeLater { AppLog.info("ウィンドウがフォーカスを得ました: ${window.describeFocus()}") }
    }

    override fun windowLostFocus(event: WindowEvent) {
        val destination = event.oppositeWindow?.javaClass?.simpleName ?: OTHER_APPLICATION
        AppLog.info("ウィンドウがフォーカスを失いました: 移った先=$destination ${window.describeFocus()}")
    }

    private companion object {
        const val OTHER_APPLICATION = "ほかのアプリ"
    }
}

/** ウィンドウが手前で操作を受けているか、中のどの部品がフォーカスを持っているかを、記録用の文字にする。 */
internal fun Window.describeFocus(): String =
    "active=$isActive focused=$isFocused " +
        "focusOwner=${focusOwner?.javaClass?.simpleName} mostRecentFocusOwner=${mostRecentFocusOwner?.javaClass?.simpleName}"
