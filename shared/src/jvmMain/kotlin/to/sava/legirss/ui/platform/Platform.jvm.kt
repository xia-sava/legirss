package to.sava.legirss.ui.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

actual val HorizontalResizePointerIcon: PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual val VerticalResizePointerIcon: PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}

@Composable
actual fun ContentScrollbar(state: ScrollState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}

private val listDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

actual fun formatListDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(listDateTimeFormatter)
