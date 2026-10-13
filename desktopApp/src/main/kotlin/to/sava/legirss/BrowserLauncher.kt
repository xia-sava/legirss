package to.sava.legirss

import java.awt.Desktop
import java.awt.Window
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * URL を既定のブラウザで開く。開いたあとも、このアプリのウィンドウを手前に保つ。
 *
 * ブラウザは開くときに自分を手前へ出すので、それを待ってからウィンドウを手前へ戻す。
 * 続けて何件も開きながら読み進められるようにするためのもの。
 */
internal class BrowserLauncher {
    /** 手前に保つウィンドウ。null の間は、開いたあとに何もしない。 */
    @Volatile
    var ownerWindow: Window? = null

    // ブラウザを開く呼び出しは返るまでに数秒かかることがあるので、画面のスレッドでは行わない。
    // 続けて開いた URL が、頼んだ順に開くよう、1 本のスレッドで順に処理する。
    private val opener = Executors.newSingleThreadExecutor { task -> Thread(task, "browser-launcher").apply { isDaemon = true } }

    /** [url] を開く。すぐに戻り、開く処理は裏で進む。開けなかった場合は理由を記録する。 */
    fun open(url: String) {
        opener.execute {
            val requestedAt = System.currentTimeMillis()
            if (!browse(url)) return@execute
            AppLog.info("ブラウザを開く呼び出しが戻りました: ${System.currentTimeMillis() - requestedAt} ms")
            SwingUtilities.invokeLater { ownerWindow?.takeIf { it.isVisible }?.let(::bringBackWhenCovered) }
        }
    }

    /** @return 開けたら true。 */
    private fun browse(url: String): Boolean =
        try {
            Desktop.getDesktop().browse(URI(url))
            true
        } catch (exception: URISyntaxException) {
            AppLog.warn("URL として扱えません: $url", exception)
            false
        } catch (exception: IOException) {
            AppLog.warn("ブラウザで開けません: $url", exception)
            false
        }

    /**
     * [window] が手前でなくなるのを待って、手前へ戻す。一定の時間のうちに変わらなければ何もしない。
     *
     * 手前へ戻すのは 1 度だけ。その前後で [window] が手前かどうかが変わるたびに、フォーカスの状態を記録する。
     */
    private fun bringBackWhenCovered(window: Window) {
        val startedAt = System.currentTimeMillis()
        fun record(event: String) = AppLog.info("$event: ${System.currentTimeMillis() - startedAt} ms ${window.describeFocus()}")

        var remainingChecks = MAX_CHECKS
        var broughtBack = false
        var wasActive = window.isActive
        record("手前へ戻す監視を始めます")
        Timer(CHECK_INTERVAL_MILLIS) { event ->
            remainingChecks -= 1
            val active = window.isActive
            if (active != wasActive) record(if (active) "ウィンドウが手前になりました" else "ウィンドウが手前でなくなりました")
            wasActive = active
            if (!active && !broughtBack) {
                broughtBack = true
                bringToFront(window)
                record("手前へ戻す要求を出しました")
            }
            if (remainingChecks <= 0) {
                record("手前へ戻す監視を終えます")
                (event.source as Timer).stop()
            }
        }.start()
    }

    private fun bringToFront(window: Window) {
        // 手前へ出す要求だけでは OS に退けられることがあるので、いったん常に手前の指定を付けて確実に重ね順を上げる。
        val wasAlwaysOnTop = window.isAlwaysOnTop
        window.isAlwaysOnTop = true
        window.toFront()
        // キーを受け取るのはウィンドウの中の部品なので、フォーカスはウィンドウ自身ではなく、直前に持っていた部品へ戻す。
        window.mostRecentFocusOwner?.requestFocus()
        window.isAlwaysOnTop = wasAlwaysOnTop
    }

    private companion object {
        const val CHECK_INTERVAL_MILLIS = 50
        const val MAX_CHECKS = 60
    }
}
