package to.sava.legirss.ui.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon

/** 左右に動かせる境目の上で出すマウスポインタ。 */
expect val HorizontalResizePointerIcon: PointerIcon

/** 上下に動かせる境目の上で出すマウスポインタ。 */
expect val VerticalResizePointerIcon: PointerIcon

@Composable
expect fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier)

@Composable
expect fun ContentScrollbar(state: ScrollState, modifier: Modifier = Modifier)

/** 一覧の日付列に出す文字列。端末のタイムゾーンで `MM/dd HH:mm` の形にする。 */
expect fun formatListDateTime(epochMillis: Long): String
