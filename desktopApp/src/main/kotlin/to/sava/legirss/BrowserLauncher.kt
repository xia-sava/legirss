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
            if (browse(url)) SwingUtilities.invokeLater { ownerWindow?.takeIf { it.isVisible }?.let(::bringBackWhenCovered) }
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

    /** [window] が手前でなくなるのを待って、手前へ戻す。一定の時間のうちに変わらなければ何もしない。 */
    private fun bringBackWhenCovered(window: Window) {
        var remainingChecks = MAX_CHECKS
        Timer(CHECK_INTERVAL_MILLIS) { event ->
            remainingChecks -= 1
            val covered = !window.isActive
            if (covered) bringToFront(window)
            if (covered || remainingChecks <= 0) (event.source as Timer).stop()
        }.start()
    }

    private fun bringToFront(window: Window) {
        // 手前へ出す要求だけでは OS に退けられることがあるので、いったん常に手前の指定を付けて確実に重ね順を上げる。
        val wasAlwaysOnTop = window.isAlwaysOnTop
        window.isAlwaysOnTop = true
        window.toFront()
        window.requestFocus()
        window.isAlwaysOnTop = wasAlwaysOnTop
    }

    private companion object {
        const val CHECK_INTERVAL_MILLIS = 50
        const val MAX_CHECKS = 60
    }
}
