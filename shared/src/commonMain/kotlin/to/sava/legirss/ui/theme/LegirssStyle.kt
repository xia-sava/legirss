package to.sava.legirss.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class LegirssColors(
    val background: Color = Color(0xFFFFFFFF),
    val panel: Color = Color(0xFFF0F0F0),
    val border: Color = Color(0xFFC8C8C8),
    val text: Color = Color(0xFF000000),
    val textDim: Color = Color(0xFF6E6E6E),
    val link: Color = Color(0xFF0B57D0),
    val selection: Color = Color(0xFFCCE4F7),
    val selectionInactive: Color = Color(0xFFE4E4E4),
    val rowAlternate: Color = Color(0xFFF4F4F4),
    val hover: Color = Color(0xFFE5F1FB),
    val iconGroup: Color = Color(0xFFE2B04A),
    val iconChannel: Color = Color(0xFFF08A24),
    val iconPaused: Color = Color(0xFFA8A8A8),
    val iconError: Color = Color(0xFFD13438),
    val iconUnread: Color = Color(0xFF2B7CD3),
    val iconProtected: Color = Color(0xFFD9A400),
)

/**
 * 画面の寸法と文字の設定。
 *
 * 文字の大きさは 96dpi でのピクセル数に合わせてある（9pt = 12px、10pt = 13px）。
 */
@Immutable
data class LegirssStyle(
    val colors: LegirssColors = LegirssColors(),
    val fontFamily: FontFamily = FontFamily.Default,
    val uiFontSize: TextUnit = 12.sp,
    val treeFontSize: TextUnit = 12.sp,
    val listFontSize: TextUnit = 13.sp,
    val articleFontSize: TextUnit = 14.sp,
    val toolbarHeight: Dp = 24.dp,
    val statusBarHeight: Dp = 20.dp,
    val headerHeight: Dp = 20.dp,
    val splitterThickness: Dp = 5.dp,
    val treeIndent: Dp = 16.dp,
) {
    val treeRowHeight: Dp get() = rowHeightFor(treeFontSize)
    val listRowHeight: Dp get() = rowHeightFor(listFontSize)

    fun textStyle(fontSize: TextUnit = uiFontSize, bold: Boolean = false, color: Color = colors.text): TextStyle =
        TextStyle(
            color = color,
            fontSize = fontSize,
            fontFamily = fontFamily,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )

    private fun rowHeightFor(fontSize: TextUnit): Dp = (fontSize.value + ROW_VERTICAL_SPACE).dp

    private companion object {
        // 文字の上下に取る余白の合計。
        const val ROW_VERTICAL_SPACE = 5f
    }
}

val LocalLegirssStyle = staticCompositionLocalOf { LegirssStyle() }
