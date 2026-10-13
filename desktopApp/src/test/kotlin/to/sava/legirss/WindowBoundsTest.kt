package to.sava.legirss

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowBoundsTest {
    private val screen = Rectangle(0, 0, 1920, 1080)

    /** 設定に直した位置と大きさを読み戻すと、元と同じになる。 */
    @Test
    fun restoresFromOwnSettings() {
        val bounds = WindowBounds(x = -1200, y = 40, width = 1100, height = 720)

        assertEquals(bounds, WindowBounds.fromSettings(bounds.toSettings()))
    }

    /** 保存されていない項目があるか、大きさが小さすぎれば、読み戻せないものとして扱う。 */
    @Test
    fun rejectsIncompleteOrTooSmallSettings() {
        val complete = WindowBounds(x = 10, y = 20, width = 800, height = 600).toSettings()

        assertNull(WindowBounds.fromSettings(emptyMap()))
        assertNull(WindowBounds.fromSettings(complete - "window_y"))
        assertNull(WindowBounds.fromSettings(complete + ("window_width" to "50")))
    }

    /** 画面に十分な広さが出ていれば届くと判定し、画面の外や、端にわずかに掛かるだけなら届かないと判定する。 */
    @Test
    fun judgesWhetherWindowIsReachable() {
        val secondScreen = Rectangle(-1920, 0, 1920, 1080)

        assertTrue(WindowBounds(100, 100, 800, 600).isReachableOn(listOf(screen)))
        assertTrue(WindowBounds(1700, 900, 800, 600).isReachableOn(listOf(screen)))
        assertFalse(WindowBounds(-32000, -32000, 800, 600).isReachableOn(listOf(screen)))
        assertFalse(WindowBounds(1900, 100, 800, 600).isReachableOn(listOf(screen)))
        assertFalse(WindowBounds(-1500, 100, 800, 600).isReachableOn(listOf(screen)))
        assertTrue(WindowBounds(-1500, 100, 800, 600).isReachableOn(listOf(screen, secondScreen)))
    }
}
