package to.sava.legirss.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    /** 設定に直した値を読み戻すと、元と同じ設定になる。 */
    @Test
    fun restoresFromOwnSettings() {
        val settings = AppSettings(
            treeFontSize = 11,
            listFontSize = 15,
            articleFontSize = 18,
            updateIntervalMinutes = 120,
            retention = Retention(keepCount = 30, keepDays = 14),
            centerSelection = false,
        )

        assertEquals(settings, AppSettings.fromSettings(settings.toSettings()))
    }

    /** 保存されていない項目と、数として読めない値は、既定値になる。 */
    @Test
    fun fallsBackToDefaultsForMissingOrUnreadableValues() {
        val restored = AppSettings.fromSettings(mapOf("list_font_size" to "large", "keep_days" to "14"))

        assertEquals(AppSettings(retention = Retention(keepCount = 100, keepDays = 14)), restored)
    }

    /** 範囲を外れた値は、範囲の端に収める。 */
    @Test
    fun clampsValuesIntoRange() {
        val restored = AppSettings.fromSettings(
            mapOf("tree_font_size" to "2", "update_interval_minutes" to "1", "keep_count" to "-5", "keep_days" to "99999"),
        )

        assertEquals(
            AppSettings(treeFontSize = 8, updateIntervalMinutes = 30, retention = Retention(keepCount = 0, keepDays = 3650)),
            restored,
        )
    }
}
