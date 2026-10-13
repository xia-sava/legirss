package to.sava.legirss

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JCheckBoxMenuItem
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.math.roundToInt

/** タスクトレイのアイコンで表す状態。色で見分ける。 */
enum class TrayState(val color: Color) {
    /** 未読が無い。 */
    NORMAL(Color(0x3F, 0xA9, 0x4B)),

    /** 未読がある。 */
    UNREAD(Color(0xF0, 0x8A, 0x24)),

    UPDATING(Color(0x2B, 0x7C, 0xD3)),
}

/**
 * タスクトレイのアイコンとメニュー。
 *
 * 左クリックでウィンドウの表示と非表示を切り替え、右クリックでメニューを出す。
 */
class TrayController(
    private val onToggleWindow: () -> Unit,
    private val onShowWindow: () -> Unit,
    private val onUpdate: () -> Unit,
    private val onExit: () -> Unit,
    private val startup: StartupRegistration,
) {
    private val icons = TrayState.entries.associateWith(::drawIcon)
    private var trayIcon: TrayIcon? = null

    /** アイコンをタスクトレイに出す。タスクトレイを使えない環境では何もしない。 */
    fun install() {
        if (!SystemTray.isSupported()) return
        trayIcon = TrayIcon(icons.getValue(TrayState.NORMAL), APP_TITLE).apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) onToggleWindow()
                }

                override fun mouseReleased(event: MouseEvent) {
                    if (event.isPopupTrigger) showMenu()
                }
            })
        }.also(SystemTray.getSystemTray()::add)
    }

    fun update(state: TrayState, tooltip: String) {
        trayIcon?.apply {
            image = icons.getValue(state)
            toolTip = tooltip
        }
    }

    fun remove() {
        trayIcon?.let(SystemTray.getSystemTray()::remove)
        trayIcon = null
    }

    /**
     * マウスポインタの位置にメニューを出す。
     *
     * タスクトレイのアイコンは Swing の部品ではないので、見えない小さなウィンドウを足場にして出し、
     * その足場からフォーカスが外れたらメニューを閉じる。
     */
    private fun showMenu() {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        val anchor = JDialog().apply {
            isUndecorated = true
            isAlwaysOnTop = true
            setSize(1, 1)
        }
        val menu = createMenu()
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit

            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = anchor.dispose()

            override fun popupMenuCanceled(event: PopupMenuEvent) = anchor.dispose()
        })
        anchor.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) {
                menu.isVisible = false
            }
        })
        // タスクトレイは画面の端にあるので、メニューはポインタの左上へ開く。
        val size = menu.preferredSize
        anchor.setLocation(pointer.x, pointer.y)
        anchor.isVisible = true
        menu.show(anchor, -size.width, -size.height)
    }

    internal fun createMenu(): JPopupMenu = JPopupMenu().apply {
        add(menuItem(JMenuItem(SHOW_LABEL)) { onShowWindow() })
        add(menuItem(JMenuItem(UPDATE_LABEL)) { onUpdate() })
        addSeparator()
        add(createStartupItem())
        addSeparator()
        add(menuItem(JMenuItem(EXIT_LABEL)) { onExit() })
    }

    private fun createStartupItem(): JMenuItem {
        val item = JCheckBoxMenuItem(STARTUP_LABEL, startup.isAvailable && startup.isRegistered())
        item.isEnabled = startup.isAvailable
        return menuItem(item) {
            val succeeded = if (item.isSelected) startup.register() else startup.unregister()
            // 失敗したら、チェックの表示を実際の登録の状態に合わせ直す。
            if (!succeeded) item.isSelected = startup.isRegistered()
        }
    }

    /** 項目の高さを文字の高さに合わせて決め、選ばれたときの動作を付ける。 */
    private fun <T : JMenuItem> menuItem(item: T, onSelect: () -> Unit): T = item.apply {
        // 既定の上下の余白を外してから高さを決める。残したままだと文字が行の中央に来ない。
        margin = Insets(0, margin.left, 0, margin.right)
        border = BorderFactory.createEmptyBorder(0, border.getBorderInsets(this).left, 0, border.getBorderInsets(this).right)
        val textHeight = getFontMetrics(font).height
        preferredSize = Dimension(preferredSize.width, (textHeight * MENU_ITEM_HEIGHT_RATIO).roundToInt())
        addActionListener { onSelect() }
    }

    companion object {
        private const val APP_TITLE = "legirss"
        private const val SHOW_LABEL = "表示"
        private const val UPDATE_LABEL = "更新"
        private const val STARTUP_LABEL = "Windows の起動時に開始する"
        private const val EXIT_LABEL = "終了"

        // メニューの 1 行の高さを、文字の高さの何倍にするか。
        private const val MENU_ITEM_HEIGHT_RATIO = 1.2

        private const val ICON_SIZE = 32

        /** 状態ごとの色で塗った角の丸い四角に、電波を表す点と弧を描く。 */
        internal fun drawIcon(state: TrayState): BufferedImage = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                color = state.color
                fillRoundRect(1, 1, 30, 30, 8, 8)
                color = Color.WHITE
                fillOval(6, 21, 6, 6)
                stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                draw(Arc2D.Double(-1.0, 14.0, 20.0, 20.0, 0.0, 90.0, Arc2D.OPEN))
                draw(Arc2D.Double(-8.0, 7.0, 34.0, 34.0, 0.0, 90.0, Arc2D.OPEN))
                dispose()
            }
        }
    }
}
