package to.sava.legirss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import to.sava.legirss.ui.component.DialogFrame

/**
 * ダイアログを OS のダイアログとして出す枠。持ち主のウィンドウの中央に、中身の大きさで出す。Esc で閉じる。
 *
 * 出している間、持ち主のウィンドウは操作を受け付けない。
 */
object WindowDialogFrame : DialogFrame {
    @Composable
    override fun Show(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize.Unspecified),
            title = title,
            resizable = false,
            onPreviewKeyEvent = { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onDismiss() }
            },
        ) {
            LaunchedEffect(Unit) { window.setLocationRelativeTo(window.owner) }
            content()
        }
    }
}
