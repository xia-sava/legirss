package to.sava.legirss.ui.component

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/** ドラッグしている間に、位置を測るための部品の配置を覚えておく入れ物。 */
private class DragAnchor(private val orientation: Orientation) {
    var coordinates: LayoutCoordinates? = null

    /** 部品の中の位置を、画面全体の座標での、ドラッグの向きの位置にする。 */
    fun rootPosition(local: Offset): Float {
        val root = coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: local
        return if (orientation == Orientation.Horizontal) root.x else root.y
    }
}

/**
 * [orientation] の向きのドラッグを受け、押した位置からの移動量（ピクセル）を伝える。
 *
 * 移動量は画面全体の座標で測る。ドラッグに合わせてこの部品が動いても、マウスの移動量がそのまま伝わる。
 * 少し動かすまではドラッグとして扱わないので、動かさずに離したクリックはほかの部品が受け取れる。
 *
 * @param onStart ドラッグを始めた。
 * @param onDrag 押した位置からの移動量。
 * @param onEnd ドラッグを終えた。ボタンを離して終えたら true、取り消されたら false を伝える。
 */
fun Modifier.dragInRoot(
    orientation: Orientation,
    onStart: () -> Unit = {},
    onDrag: (Float) -> Unit,
    onEnd: (Boolean) -> Unit = {},
): Modifier = composed {
    val anchor = remember(orientation) { DragAnchor(orientation) }
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEnd by rememberUpdatedState(onEnd)
    onGloballyPositioned { anchor.coordinates = it }
        .pointerInput(orientation) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = anchor.rootPosition(down.position)
                val first = awaitTouchSlop(orientation, down.id) ?: return@awaitEachGesture
                currentOnStart()
                currentOnDrag(anchor.rootPosition(first.position) - start)
                var completed = false
                try {
                    completed = drag(orientation, first.id) { change ->
                        change.consume()
                        currentOnDrag(anchor.rootPosition(change.position) - start)
                    }
                } finally {
                    currentOnEnd(completed)
                }
            }
        }
}

private suspend fun AwaitPointerEventScope.awaitTouchSlop(orientation: Orientation, pointerId: PointerId): PointerInputChange? =
    when (orientation) {
        Orientation.Horizontal -> awaitHorizontalTouchSlopOrCancellation(pointerId) { change, _ -> change.consume() }
        Orientation.Vertical -> awaitVerticalTouchSlopOrCancellation(pointerId) { change, _ -> change.consume() }
    }

private suspend fun AwaitPointerEventScope.drag(
    orientation: Orientation,
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): Boolean = when (orientation) {
    Orientation.Horizontal -> horizontalDrag(pointerId, onDrag)
    Orientation.Vertical -> verticalDrag(pointerId, onDrag)
}
