package to.sava.legirss

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import to.sava.legirss.ui.MainActions
import to.sava.legirss.ui.handleKey

/**
 * メインウィンドウが受けたキーのうち、メニューの項目のショートカットで受けないものを処理する。
 *
 * ダイアログを出している間は、後ろの画面のキー操作を受け付けない。
 *
 * @param dialogShown ダイアログを手前に出しているか。
 * @param onCloseWindow ウィンドウをしまう。
 * @return 処理したら true。
 */
internal fun handleWindowKey(event: KeyEvent, dialogShown: Boolean, actions: MainActions, onCloseWindow: () -> Unit): Boolean = when {
    dialogShown -> false
    // Esc はメニューのショートカットに届かないので、ここで受ける。
    event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
        onCloseWindow()
        true
    }
    else -> actions.handleKey(event)
}
