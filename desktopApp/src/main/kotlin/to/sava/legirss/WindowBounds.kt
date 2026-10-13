package to.sava.legirss

import java.awt.Rectangle

/** 最大化していないときのウィンドウの位置と大きさ。単位は画面の拡大率を掛ける前の点。 */
internal data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    /** 設定として保存する、名前と値の組。 */
    fun toSettings(): Map<String, String> = mapOf(
        X_KEY to x.toString(),
        Y_KEY to y.toString(),
        WIDTH_KEY to width.toString(),
        HEIGHT_KEY to height.toString(),
    )

    /**
     * [screens] のどれかの画面に、つかんで動かせるだけの広さが出ているか。
     *
     * 画面の構成が保存したときと変わっていると、保存した位置が画面の外になることがある。
     */
    fun isReachableOn(screens: List<Rectangle>): Boolean {
        val window = Rectangle(x, y, width, height)
        return screens.map(window::intersection).any { it.width >= MIN_REACHABLE_SIZE && it.height >= MIN_REACHABLE_SIZE }
    }

    companion object {
        private const val X_KEY = "window_x"
        private const val Y_KEY = "window_y"
        private const val WIDTH_KEY = "window_width"
        private const val HEIGHT_KEY = "window_height"

        /** ウィンドウを最大化しているかを保存する設定の名前。値は [MAXIMIZED_ON] か [MAXIMIZED_OFF]。 */
        const val MAXIMIZED_KEY = "window_maximized"
        const val MAXIMIZED_ON = "1"
        const val MAXIMIZED_OFF = "0"

        private const val MIN_REACHABLE_SIZE = 80
        private const val MIN_WINDOW_SIZE = 200

        /** 保存した設定から組み立てる。保存されていないか、値が位置と大きさとして読めなければ null。 */
        fun fromSettings(settings: Map<String, String>): WindowBounds? {
            fun intOf(key: String): Int? = settings[key]?.toIntOrNull()
            val width = intOf(WIDTH_KEY)?.takeIf { it >= MIN_WINDOW_SIZE } ?: return null
            val height = intOf(HEIGHT_KEY)?.takeIf { it >= MIN_WINDOW_SIZE } ?: return null
            return WindowBounds(x = intOf(X_KEY) ?: return null, y = intOf(Y_KEY) ?: return null, width = width, height = height)
        }
    }
}
