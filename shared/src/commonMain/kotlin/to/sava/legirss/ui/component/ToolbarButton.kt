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

/** ツールバーに置く、文字だけのボタン。マウスを載せると背景が変わる。 */
@Composable
fun ToolbarButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val style = LocalLegirssStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (hovered) style.colors.hover else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = BUTTON_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = style.textStyle())
    }
}
