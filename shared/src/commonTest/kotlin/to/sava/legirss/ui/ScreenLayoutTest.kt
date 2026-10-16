package to.sava.legirss.ui

import androidx.compose.ui.unit.dp
import to.sava.legirss.ui.ArticleColumn.AUTHOR
import to.sava.legirss.ui.ArticleColumn.CATEGORY
import to.sava.legirss.ui.ArticleColumn.CHANNEL
import to.sava.legirss.ui.ArticleColumn.DATE
import to.sava.legirss.ui.ArticleColumn.FETCHED
import to.sava.legirss.ui.ArticleColumn.STATE
import to.sava.legirss.ui.ArticleColumn.TITLE
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenLayoutTest {
    /** 設定に直した寸法と列の並びを読み戻すと、元と同じになる。 */
    @Test
    fun restoresFromOwnSettings() {
        val layout = ScreenLayout(treeWidth = 180.5.dp, listHeight = 300.dp)
            .withColumnWidth(ArticleColumn.TITLE, 512.dp)
            .withColumnMoved(ArticleColumn.AUTHOR, before = ArticleColumn.TITLE)
            .withColumnVisible(ArticleColumn.CATEGORY, visible = false)

        assertEquals(layout, ScreenLayout.fromSettings(layout.toSettings()))
    }

    /** 列を指定の列の左へ移せる。移す先が無ければ右端へ移す。 */
    @Test
    fun movesColumn() {
        val layout = ScreenLayout()

        assertEquals(
            listOf(STATE, DATE, AUTHOR, FETCHED, TITLE, CHANNEL, CATEGORY),
            layout.withColumnMoved(AUTHOR, before = FETCHED).columnOrder,
        )
        assertEquals(
            listOf(DATE, FETCHED, TITLE, CHANNEL, CATEGORY, AUTHOR, STATE),
            layout.withColumnMoved(STATE, before = null).columnOrder,
        )
    }

    /** 隠した列は出す列から外れ、並びの中の位置は保つ。状態の印とタイトルは隠せない。 */
    @Test
    fun hidesOnlyHideableColumns() {
        val layout = ScreenLayout()
            .withColumnVisible(CHANNEL, visible = false)
            .withColumnVisible(TITLE, visible = false)
            .withColumnVisible(STATE, visible = false)

        assertEquals(listOf(STATE, DATE, FETCHED, TITLE, CATEGORY, AUTHOR), layout.visibleColumns)
        assertEquals(ArticleColumn.entries, layout.withColumnVisible(CHANNEL, visible = true).visibleColumns)
    }

    /** 保存した並びに無い列は、既定の並びで直前にある列の右に置く。知らない名前と、隠せない列を隠す指定は無視する。 */
    @Test
    fun completesColumnOrderFromSettings() {
        val restored = ScreenLayout.fromSettings(
            mapOf("column_order" to "title,unknown,state,date", "column_hidden" to "title,author"),
        )

        assertEquals(listOf(TITLE, CHANNEL, CATEGORY, AUTHOR, STATE, DATE, FETCHED), restored.columnOrder)
        assertEquals(setOf(AUTHOR), restored.hiddenColumns)
    }

    /** 保存されていない寸法と、寸法として読めない値・正でない値は、既定値になる。 */
    @Test
    fun fallsBackToDefaultsForMissingOrInvalidValues() {
        val restored = ScreenLayout.fromSettings(
            mapOf("tree_width" to "wide", "list_height" to "-10", "column_width_date" to "96.0"),
        )

        assertEquals(ScreenLayout().withColumnWidth(ArticleColumn.DATE, 96.dp), restored)
    }
}
