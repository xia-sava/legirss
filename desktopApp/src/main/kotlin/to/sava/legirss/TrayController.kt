package to.sava.legirss

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
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
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicCheckBoxMenuItemUI
import javax.swing.plaf.basic.BasicMenuItemUI
import kotlin.math.roundToInt

private class CenteredMenuItemUI : BasicMenuItemUI() {
    override fun paintText(graphics: Graphics, menuItem: JMenuItem, textRect: Rectangle, text: String) {
        drawVisuallyCentered(graphics, menuItem, textRect, text, selectionForeground, disabledForeground)
    }
}

private class CenteredCheckBoxMenuItemUI : BasicCheckBoxMenuItemUI() {
    override fun paintText(graphics: Graphics, menuItem: JMenuItem, textRect: Rectangle, text: String) {
        drawVisuallyCentered(graphics, menuItem, textRect, text, selectionForeground, disabledForeground)
    }
}

// 文字の見た目の高さを測るのに使う字。漢字の字面は、かなや英字より安定して枠いっぱいに近い。
private const val REFERENCE_GLYPH = "国"

// 画面の文字描画の設定（アンチエイリアスの方式など）を持つデスクトップのプロパティ。
private const val DESKTOP_FONT_HINTS = "awt.font.desktophints"

// 選べない項目の文字色。項目用の色が定義されていない見た目のときに使う。
private const val INACTIVE_TEXT_COLOR_KEY = "textInactiveText"

/**
 * 文字の見た目の中心が、項目の高さのちょうど半分に来るように描く。
 *
 * 標準の配置は、フォントが持つ行の箱（上端から下端まで）を中央に置く。上側に広い余白を持つフォントでは、
 * そのままだと文字が下に寄って見えるので、字面の位置から描く位置を決める。
 */
private fun drawVisuallyCentered(
    graphics: Graphics,
    menuItem: JMenuItem,
    textRect: Rectangle,
    text: String,
    selectionForeground: Color?,
    disabledForeground: Color?,
) {
    val graphics2D = graphics as Graphics2D
    (Toolkit.getDefaultToolkit().getDesktopProperty(DESKTOP_FONT_HINTS) as? Map<*, *>)?.let(graphics2D::addRenderingHints)
    graphics2D.font = menuItem.font
    graphics2D.color = when {
        !menuItem.model.isEnabled -> disabledForeground ?: UIManager.getColor(INACTIVE_TEXT_COLOR_KEY) ?: Color.GRAY
        menuItem.model.isArmed -> selectionForeground ?: menuItem.foreground
        else -> menuItem.foreground
    }
    // 字面の中心は、ベースラインを 0 とした位置（下向きが正）で求まる。
    val glyphCenter = menuItem.font.createGlyphVector(graphics2D.fontRenderContext, REFERENCE_GLYPH).visualBounds.centerY
    val baseline = menuItem.height / 2.0 - glyphCenter
    graphics2D.drawString(text, textRect.x.toFloat(), baseline.toFloat())
}

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
    private val onOpenSettings: () -> Unit,
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
        add(menuItem(JMenuItem(SETTINGS_LABEL)) { onOpenSettings() })
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
        // OS の見た目に合わせた描画は、文字の位置を OS の決めた余白で置くので、行を詰めると文字が下に寄る。
        // 文字を行の上下中央に置く描画に替え、上下の余白も外す。
        setUI(if (this is JCheckBoxMenuItem) CenteredCheckBoxMenuItemUI() else CenteredMenuItemUI())
        margin = Insets(0, MENU_ITEM_SIDE_PADDING, 0, MENU_ITEM_SIDE_PADDING)
        border = BorderFactory.createEmptyBorder()
        val textHeight = getFontMetrics(font).height
        preferredSize = Dimension(preferredSize.width, (textHeight * MENU_ITEM_HEIGHT_RATIO).roundToInt())
        addActionListener { onSelect() }
    }

    companion object {
        private const val APP_TITLE = "legirss"
        private const val SHOW_LABEL = "表示"
        private const val UPDATE_LABEL = "更新"
        private const val SETTINGS_LABEL = "設定…"
        private const val STARTUP_LABEL ="Windows の起動時に開始する"
        private const val EXIT_LABEL = "終了"

        // メニューの 1 行の高さを、文字の高さの何倍にするか。
        private const val MENU_ITEM_HEIGHT_RATIO = 1.2
        private const val MENU_ITEM_SIDE_PADDING = 8

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
