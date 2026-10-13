package to.sava.legirss.model

/** 利用者が設定画面で変えられる項目。 */
data class AppSettings(
    /** ツリーの文字の大きさ。単位は 96dpi でのピクセル数。 */
    val treeFontSize: Int = 12,
    /** 記事の一覧の文字の大きさ。 */
    val listFontSize: Int = 13,
    /** 記事の本文の文字の大きさ。 */
    val articleFontSize: Int = 14,
    /** 定期更新の間隔（分）。 */
    val updateIntervalMinutes: Int = 60,
    val retention: Retention = Retention.DEFAULT,
    /** キー操作で選択を動かしたとき、選択行を一覧の中央付近へ寄せるか。 */
    val centerSelection: Boolean = true,
) {
    /** どの項目も取りうる範囲に収めた設定。 */
    fun validated(): AppSettings = copy(
        treeFontSize = treeFontSize.coerceIn(FONT_SIZE_RANGE),
        listFontSize = listFontSize.coerceIn(FONT_SIZE_RANGE),
        articleFontSize = articleFontSize.coerceIn(FONT_SIZE_RANGE),
        updateIntervalMinutes = updateIntervalMinutes.coerceIn(UPDATE_INTERVAL_RANGE),
        retention = Retention(
            keepCount = retention.keepCount.coerceIn(KEEP_COUNT_RANGE),
            keepDays = retention.keepDays.coerceIn(KEEP_DAYS_RANGE),
        ),
    )

    /** 設定として保存する、名前と値の組。 */
    fun toSettings(): Map<String, String> = mapOf(
        TREE_FONT_SIZE_KEY to treeFontSize.toString(),
        LIST_FONT_SIZE_KEY to listFontSize.toString(),
        ARTICLE_FONT_SIZE_KEY to articleFontSize.toString(),
        UPDATE_INTERVAL_KEY to updateIntervalMinutes.toString(),
        Retention.KEEP_COUNT_KEY to retention.keepCount.toString(),
        Retention.KEEP_DAYS_KEY to retention.keepDays.toString(),
        CENTER_SELECTION_KEY to if (centerSelection) ENABLED else DISABLED,
    )

    companion object {
        val FONT_SIZE_RANGE = 8..32
        val UPDATE_INTERVAL_RANGE = 30..1440
        val KEEP_COUNT_RANGE = 0..10_000
        val KEEP_DAYS_RANGE = 1..3650

        private const val TREE_FONT_SIZE_KEY = "tree_font_size"
        private const val LIST_FONT_SIZE_KEY = "list_font_size"
        private const val ARTICLE_FONT_SIZE_KEY = "article_font_size"
        private const val UPDATE_INTERVAL_KEY = "update_interval_minutes"
        private const val CENTER_SELECTION_KEY = "center_selection"
        private const val ENABLED = "1"
        private const val DISABLED = "0"

        /** 保存した設定から組み立てる。保存されていない項目と読めない値は既定値に、範囲を外れた値は範囲の端になる。 */
        fun fromSettings(settings: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            fun intOf(key: String, default: Int): Int = settings[key]?.toIntOrNull() ?: default
            return AppSettings(
                treeFontSize = intOf(TREE_FONT_SIZE_KEY, defaults.treeFontSize),
                listFontSize = intOf(LIST_FONT_SIZE_KEY, defaults.listFontSize),
                articleFontSize = intOf(ARTICLE_FONT_SIZE_KEY, defaults.articleFontSize),
                updateIntervalMinutes = intOf(UPDATE_INTERVAL_KEY, defaults.updateIntervalMinutes),
                retention = Retention(
                    keepCount = intOf(Retention.KEEP_COUNT_KEY, defaults.retention.keepCount),
                    keepDays = intOf(Retention.KEEP_DAYS_KEY, defaults.retention.keepDays),
                ),
                centerSelection = settings[CENTER_SELECTION_KEY]?.let { it == ENABLED } ?: defaults.centerSelection,
            ).validated()
        }
    }
}
