package to.sava.legirss.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * メイン画面の中の、利用者が動かせる寸法と、一覧の列の並び。
 *
 * @param columnOrder 一覧の列を左から並べた順。隠した列も含む。
 * @param hiddenColumns 隠している列。
 */
@Immutable
data class ScreenLayout(
    val treeWidth: Dp = DEFAULT_TREE_WIDTH,
    val listHeight: Dp = DEFAULT_LIST_HEIGHT,
    val columnWidths: Map<ArticleColumn, Dp> = DEFAULT_COLUMN_WIDTHS,
    val columnOrder: List<ArticleColumn> = ArticleColumn.entries,
    val hiddenColumns: Set<ArticleColumn> = emptySet(),
) {
    /** 一覧に出す列を、左から並べた順。 */
    val visibleColumns: List<ArticleColumn> get() = columnOrder.filter { it !in hiddenColumns }

    fun withColumnWidth(column: ArticleColumn, width: Dp): ScreenLayout = copy(columnWidths = columnWidths + (column to width))

    /** [column] を出すか隠すかを変える。隠せない列は変えない。 */
    fun withColumnVisible(column: ArticleColumn, visible: Boolean): ScreenLayout = when {
        !column.hideable -> this
        visible -> copy(hiddenColumns = hiddenColumns - column)
        else -> copy(hiddenColumns = hiddenColumns + column)
    }

    /** [column] を [before] の左へ移す。[before] が null なら右端へ移す。 */
    fun withColumnMoved(column: ArticleColumn, before: ArticleColumn?): ScreenLayout {
        if (column == before) return this
        val rest = columnOrder - column
        val index = before?.let(rest::indexOf)?.takeIf { it >= 0 } ?: rest.size
        return copy(columnOrder = rest.take(index) + column + rest.drop(index))
    }

    /** 設定として保存する、名前と値の組。 */
    fun toSettings(): Map<String, String> = buildMap {
        put(TREE_WIDTH_KEY, treeWidth.value.toString())
        put(LIST_HEIGHT_KEY, listHeight.value.toString())
        columnWidths.forEach { (column, width) -> put(columnKey(column), width.value.toString()) }
        put(COLUMN_ORDER_KEY, columnOrder.joinToString(LIST_SEPARATOR, transform = ::columnName))
        put(HIDDEN_COLUMNS_KEY, hiddenColumns.joinToString(LIST_SEPARATOR, transform = ::columnName))
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
        private const val COLUMN_ORDER_KEY = "column_order"
        private const val HIDDEN_COLUMNS_KEY = "column_hidden"
        private const val LIST_SEPARATOR = ","

        private fun columnName(column: ArticleColumn): String = column.name.lowercase()

        private fun columnKey(column: ArticleColumn): String = "column_width_${columnName(column)}"

        private fun columnsOf(text: String?): List<ArticleColumn> {
            val byName = ArticleColumn.entries.associateBy(::columnName)
            return text.orEmpty().split(LIST_SEPARATOR).mapNotNull(byName::get).distinct()
        }

        /**
         * 保存した設定から組み立てる。保存されていない寸法と、寸法として読めない値は既定値になる。
         *
         * 列の並びに無い列は、既定の並びでの直前の列の右に置く。
         */
        fun fromSettings(settings: Map<String, String>): ScreenLayout {
            fun sizeOf(key: String): Dp? = settings[key]?.toFloatOrNull()?.takeIf { it > 0f }?.dp
            return ScreenLayout(
                treeWidth = sizeOf(TREE_WIDTH_KEY) ?: DEFAULT_TREE_WIDTH,
                listHeight = sizeOf(LIST_HEIGHT_KEY) ?: DEFAULT_LIST_HEIGHT,
                columnWidths = DEFAULT_COLUMN_WIDTHS.mapValues { (column, width) -> sizeOf(columnKey(column)) ?: width },
                columnOrder = completeOrder(columnsOf(settings[COLUMN_ORDER_KEY])),
                hiddenColumns = columnsOf(settings[HIDDEN_COLUMNS_KEY]).filter { it.hideable }.toSet(),
            )
        }

        private fun completeOrder(saved: List<ArticleColumn>): List<ArticleColumn> =
            ArticleColumn.entries.fold(saved) { order, column ->
                if (column in order) return@fold order
                val previous = ArticleColumn.entries.take(column.ordinal).lastOrNull { it in order }
                val index = previous?.let { order.indexOf(it) + 1 } ?: 0
                order.take(index) + column + order.drop(index)
            }
    }
}
