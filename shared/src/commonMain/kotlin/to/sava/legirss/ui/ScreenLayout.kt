package to.sava.legirss.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** メイン画面の中の、利用者が動かせる寸法。 */
@Immutable
data class ScreenLayout(
    val treeWidth: Dp = DEFAULT_TREE_WIDTH,
    val listHeight: Dp = DEFAULT_LIST_HEIGHT,
    val columnWidths: Map<ArticleColumn, Dp> = DEFAULT_COLUMN_WIDTHS,
) {
    fun withColumnWidth(column: ArticleColumn, width: Dp): ScreenLayout = copy(columnWidths = columnWidths + (column to width))

    /** 設定として保存する、名前と値の組。 */
    fun toSettings(): Map<String, String> = buildMap {
        put(TREE_WIDTH_KEY, treeWidth.value.toString())
        put(LIST_HEIGHT_KEY, listHeight.value.toString())
        columnWidths.forEach { (column, width) -> put(columnKey(column), width.value.toString()) }
    }

    companion object {
        private val DEFAULT_TREE_WIDTH = 240.dp
        private val DEFAULT_LIST_HEIGHT = 260.dp
        private val DEFAULT_COLUMN_WIDTHS = mapOf(
            ArticleColumn.STATE to 22.dp,
            ArticleColumn.DATE to 112.dp,
            ArticleColumn.FETCHED to 112.dp,
            ArticleColumn.TITLE to 460.dp,
            ArticleColumn.CHANNEL to 160.dp,
            ArticleColumn.CATEGORY to 110.dp,
            ArticleColumn.AUTHOR to 110.dp,
        )

        private const val TREE_WIDTH_KEY = "tree_width"
        private const val LIST_HEIGHT_KEY = "list_height"

        private fun columnKey(column: ArticleColumn): String = "column_width_${column.name.lowercase()}"

        /** 保存した設定から組み立てる。保存されていない寸法と、寸法として読めない値は既定値になる。 */
        fun fromSettings(settings: Map<String, String>): ScreenLayout {
            fun sizeOf(key: String): Dp? = settings[key]?.toFloatOrNull()?.takeIf { it > 0f }?.dp
            return ScreenLayout(
                treeWidth = sizeOf(TREE_WIDTH_KEY) ?: DEFAULT_TREE_WIDTH,
                listHeight = sizeOf(LIST_HEIGHT_KEY) ?: DEFAULT_LIST_HEIGHT,
                columnWidths = DEFAULT_COLUMN_WIDTHS.mapValues { (column, width) -> sizeOf(columnKey(column)) ?: width },
            )
        }
    }
}
