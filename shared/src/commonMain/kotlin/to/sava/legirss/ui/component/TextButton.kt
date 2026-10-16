package to.sava.legirss.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import to.sava.legirss.ui.theme.LocalLegirssStyle

private val BUTTON_PADDING = 8.dp

/**
 * 文字だけのボタン。マウスを載せると背景が変わる。
 *
 * @param emphasized 目に留まるよう、背景と文字に色を付けて出す。
 */
@Composable
fun TextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val style = LocalLegirssStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        emphasized && hovered -> style.colors.emphasisHover
        emphasized -> style.colors.emphasis
        hovered -> style.colors.hover
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(background)
            .hoverable(interactionSource)
            .clickable(interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = BUTTON_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = style.textStyle(color = if (emphasized) style.colors.link else style.colors.text))
    }
}
