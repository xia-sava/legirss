package to.sava.legirss.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenLayoutTest {
    /** 設定に直した寸法を読み戻すと、元と同じ寸法になる。 */
    @Test
    fun restoresFromOwnSettings() {
        val layout = ScreenLayout(treeWidth = 180.5.dp, listHeight = 300.dp).withColumnWidth(ArticleColumn.TITLE, 512.dp)

        assertEquals(layout, ScreenLayout.fromSettings(layout.toSettings()))
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
