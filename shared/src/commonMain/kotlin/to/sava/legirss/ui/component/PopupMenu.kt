package to.sava.legirss.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import to.sava.legirss.ui.theme.LocalLegirssStyle

/** メニューの 1 項目。 */
@Immutable
data class MenuEntry(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private val MENU_ROW_HEIGHT = 22.dp
private val MENU_SIDE_PADDING = 12.dp
private val MENU_VERTICAL_PADDING = 2.dp
private val SEPARATOR_VERTICAL_PADDING = 3.dp
private val BORDER_WIDTH = 1.dp

/**
 * 右クリックなどで出すメニュー。項目を選ぶか、外を押すと閉じる。
 *
 * @param entries 並べる項目。null のところには区切り線を引く。
 * @param position メニューの左上を置く位置。このメニューを置いた領域の左上からの距離で表す。
 */
@Composable
fun PopupMenu(entries: List<MenuEntry?>, position: IntOffset, onDismiss: () -> Unit) {
    val colors = LocalLegirssStyle.current.colors
    Popup(
        popupPositionProvider = remember(position) { MenuPositionProvider(position) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .border(BORDER_WIDTH, colors.border)
                .background(colors.background)
                .padding(vertical = MENU_VERTICAL_PADDING, horizontal = BORDER_WIDTH),
        ) {
            entries.forEach { entry ->
                if (entry == null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = SEPARATOR_VERTICAL_PADDING).height(BORDER_WIDTH).background(colors.border))
                } else {
                    MenuRow(entry) {
                        onDismiss()
                        entry.onClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, onClick: () -> Unit) {
    val style = LocalLegirssStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ROW_HEIGHT)
            .background(if (hovered && entry.enabled) style.colors.selection else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource, indication = null, enabled = entry.enabled, onClick = onClick)
            .padding(horizontal = MENU_SIDE_PADDING),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = entry.label,
            style = style.textStyle(color = if (entry.enabled) style.colors.text else style.colors.textDim),
            maxLines = 1,
        )
    }
}

/** 指定の位置にメニューを置く。ウィンドウからはみ出すときは、収まる側へずらす。 */
private class MenuPositionProvider(private val position: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = anchorBounds.left + position.x
        val top = anchorBounds.top + position.y
        // 下に収まらなければ、指定の位置から上へ開く。
        val fittedTop = if (top + popupContentSize.height > windowSize.height) top - popupContentSize.height else top
        return IntOffset(
            x = left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
            y = fittedTop.coerceAtLeast(0),
        )
    }
}
