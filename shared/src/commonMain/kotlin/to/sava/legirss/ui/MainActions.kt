package to.sava.legirss.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** メイン画面から呼ぶ操作。 */
interface MainActions {
    fun selectNode(selection: NodeSelection)

    fun toggleNode(selection: NodeSelection)

    fun setFilter(filter: StateFilter)

    fun cycleFilter()

    fun sortBy(column: ArticleColumn)

    fun selectArticle(index: Int)

    fun readOn()

    fun cycleSelectedState(forward: Boolean)

    fun deleteSelectedArticle()

    fun updateAll()

    fun updateSelected()
}

/** 記事の一覧にフォーカスがあるときのキー操作。処理したら true を返す。 */
fun MainActions.handleArticleListKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || event.hasModifier()) return false
    when (event.key) {
        Key.Spacebar -> readOn()
        Key.DirectionRight -> cycleSelectedState(forward = true)
        Key.DirectionLeft -> cycleSelectedState(forward = false)
        Key.Delete -> deleteSelectedArticle()
        else -> return false
    }
    return true
}

/** どこにフォーカスがあっても効くキー操作。処理したら true を返す。 */
fun MainActions.handleWindowKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    when {
        event.key == Key.F2 && event.isAltPressed -> updateSelected()
        event.key == Key.F2 -> updateAll()
        event.key == Key.K && event.isCtrlPressed -> cycleFilter()
        event.key == Key.Spacebar && !event.hasModifier() -> readOn()
        else -> return false
    }
    return true
}

private fun KeyEvent.hasModifier(): Boolean = isCtrlPressed || isAltPressed || isShiftPressed
