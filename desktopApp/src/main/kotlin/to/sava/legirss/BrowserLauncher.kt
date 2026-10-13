package to.sava.legirss

import java.awt.Desktop
import java.awt.Window
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import javax.swing.Timer

/**
 * URL を既定のブラウザで開く。開いたあとも、このアプリのウィンドウを手前に保つ。
 *
 * ブラウザは開くときに自分を手前へ出すので、それを待ってからウィンドウを手前へ戻す。
 * 続けて何件も開きながら読み進められるようにするためのもの。
 */
internal class BrowserLauncher {
    /** 手前に保つウィンドウ。null の間は、開いたあとに何もしない。 */
    var ownerWindow: Window? = null

    /** [url] を開く。開けなかった場合は理由を記録する。 */
    fun open(url: String) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (exception: URISyntaxException) {
            AppLog.warn("URL として扱えません: $url", exception)
            return
        } catch (exception: IOException) {
            AppLog.warn("ブラウザで開けません: $url", exception)
            return
        }
        ownerWindow?.takeIf { it.isVisible }?.let(::bringBackWhenCovered)
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
