package to.sava.legirss.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.sava.legirss.ui.platform.HorizontalResizePointerIcon
import to.sava.legirss.ui.platform.VerticalResizePointerIcon
import to.sava.legirss.ui.theme.LocalLegirssStyle

private val MIN_PANE_SIZE = 60.dp

/** 左右 2 つの領域を、ドラッグで動かせる境目で分ける。[firstWidth] は左の領域の幅。 */
@Composable
fun HorizontalSplit(
    firstWidth: Dp,
    onFirstWidthChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val thickness = LocalLegirssStyle.current.splitterThickness
    BoxWithConstraints(modifier) {
        val width = firstWidth.coerceIn(MIN_PANE_SIZE, (maxWidth - thickness - MIN_PANE_SIZE).coerceAtLeast(MIN_PANE_SIZE))
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(width).fillMaxHeight()) { first() }
            Splitter(
                orientation = Orientation.Horizontal,
                pointerIcon = HorizontalResizePointerIcon,
                size = width,
                onResize = onFirstWidthChange,
                modifier = Modifier.width(thickness).fillMaxHeight(),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        }
    }
}

/** 上下 2 つの領域を、ドラッグで動かせる境目で分ける。[firstHeight] は上の領域の高さ。 */
@Composable
fun VerticalSplit(
    firstHeight: Dp,
    onFirstHeightChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val thickness = LocalLegirssStyle.current.splitterThickness
    BoxWithConstraints(modifier) {
        val height = firstHeight.coerceIn(MIN_PANE_SIZE, (maxHeight - thickness - MIN_PANE_SIZE).coerceAtLeast(MIN_PANE_SIZE))
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(height).fillMaxWidth()) { first() }
            Splitter(
                orientation = Orientation.Vertical,
                pointerIcon = VerticalResizePointerIcon,
                size = height,
                onResize = onFirstHeightChange,
                modifier = Modifier.height(thickness).fillMaxWidth(),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { second() }
        }
    }
}

/**
 * 2 つの領域の境目。ドラッグで手前の領域の大きさを変える。
 *
 * @param size いまの手前の領域の大きさ。ドラッグを始めたときの大きさに、マウスの移動量を足した大きさを [onResize] に伝える。
 */
@Composable
private fun Splitter(
    orientation: Orientation,
    pointerIcon: PointerIcon,
    size: Dp,
    onResize: (Dp) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val currentSize by rememberUpdatedState(size)
    var startSize by remember { mutableStateOf(size) }
    Box(
        modifier
            .background(LocalLegirssStyle.current.colors.panel)
            .pointerHoverIcon(pointerIcon)
            .dragInRoot(
                orientation = orientation,
                onStart = { startSize = currentSize },
                onDrag = { distance -> onResize(startSize + with(density) { distance.toDp() }) },
            ),
    )
}
