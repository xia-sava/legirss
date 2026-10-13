package to.sava.legirss.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.sava.legirss.ui.theme.LocalLegirssStyle

private val BUTTON_PADDING = 8.dp
private val BORDER_WIDTH = 1.dp

/** 選択肢を横に並べ、選ばれている 1 つを押し込んだ見た目にする。 */
@Composable
fun <T> SegmentedButtons(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalLegirssStyle.current
    Row(modifier.border(BORDER_WIDTH, style.colors.border)) {
        options.forEach { option ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (option == selected) style.colors.selection else style.colors.panel)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(option) }
                    .padding(horizontal = BUTTON_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label(option), style = style.textStyle())
            }
        }
    }
}
