package to.sava.legirss

import java.awt.BasicStroke
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage

enum class TrayState {
    NORMAL,
    UPDATING,

    /** ウィンドウをしまっている間に新着があった。 */
    NEW_ARTICLES,
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
        trayIcon = TrayIcon(icons.getValue(TrayState.NORMAL), APP_TITLE, createMenu()).apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) onToggleWindow()
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

    private fun createMenu(): PopupMenu = PopupMenu().apply {
        add(MenuItem(SHOW_LABEL).apply { addActionListener { onShowWindow() } })
        add(MenuItem(UPDATE_LABEL).apply { addActionListener { onUpdate() } })
        addSeparator()
        add(createStartupItem())
        addSeparator()
        add(MenuItem(EXIT_LABEL).apply { addActionListener { onExit() } })
    }

    private fun createStartupItem(): CheckboxMenuItem =
        CheckboxMenuItem(STARTUP_LABEL, startup.isAvailable && startup.isRegistered()).apply {
            isEnabled = startup.isAvailable
            addItemListener {
                val succeeded = if (state) startup.register() else startup.unregister()
                // 失敗したら、チェックの表示を実際の登録の状態に合わせ直す。
                if (!succeeded) state = startup.isRegistered()
            }
        }

    companion object {
        private const val APP_TITLE = "legirss"
        private const val SHOW_LABEL = "表示"
        private const val UPDATE_LABEL = "更新"
        private const val STARTUP_LABEL = "Windows の起動時に開始する"
        private const val EXIT_LABEL = "終了"

        private const val ICON_SIZE = 32
        private val NORMAL_COLOR = Color(0xF0, 0x8A, 0x24)
        private val UPDATING_COLOR = Color(0x2B, 0x7C, 0xD3)
        private val BADGE_COLOR = Color(0xD1, 0x34, 0x38)

        /** 角の丸い四角に、電波を表す点と弧を描く。新着ありの状態では右上に丸い印を重ねる。 */
        internal fun drawIcon(state: TrayState): BufferedImage = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                color = if (state == TrayState.UPDATING) UPDATING_COLOR else NORMAL_COLOR
                fillRoundRect(1, 1, 30, 30, 8, 8)
                color = Color.WHITE
                fillOval(6, 21, 6, 6)
                stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                draw(Arc2D.Double(-1.0, 14.0, 20.0, 20.0, 0.0, 90.0, Arc2D.OPEN))
                draw(Arc2D.Double(-8.0, 7.0, 34.0, 34.0, 0.0, 90.0, Arc2D.OPEN))
                if (state == TrayState.NEW_ARTICLES) {
                    color = Color.WHITE
                    fillOval(17, 0, 15, 15)
                    color = BADGE_COLOR
                    fillOval(19, 2, 11, 11)
                }
                dispose()
            }
        }
    }
}
