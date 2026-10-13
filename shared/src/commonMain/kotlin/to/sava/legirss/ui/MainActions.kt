package to.sava.legirss.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    fun openSelectedArticle()

    fun openLink(url: String)

    fun updateAll()

    fun updateSelected()

    fun updateNode(selection: NodeSelection)

    fun setNodePaused(selection: NodeSelection, paused: Boolean)

    fun reloadList()

    fun openAddChannel()

    fun submitAddChannel(url: String)

    fun closeDialog()
}

/**
 * メイン画面のキー操作。一覧の中の移動（↑↓ など）は一覧が自分で処理するので、ここには来ない。
 *
 * @param hasSelectedArticle 記事を選んでいるか。
 * @param articleScroll 記事の表示領域のスクロール位置。
 * @return 処理したら true。
 */
fun MainActions.handleKey(
    event: KeyEvent,
    hasSelectedArticle: Boolean,
    articleScroll: ScrollState,
    scope: CoroutineScope,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
    when {
        event.key == Key.F2 && event.isAltPressed -> updateSelected()
        event.key == Key.F2 -> updateAll()
        event.key == Key.F3 -> openAddChannel()
        event.key == Key.F5 -> reloadList()
        event.key == Key.K && event.isCtrlPressed -> cycleFilter()
        // 記事を選んでいれば本文を先へ送り、末尾まで読んでいたら次の未読へ進む。
        event.key == Key.Spacebar && plain -> when {
            hasSelectedArticle -> scope.launch { if (!articleScroll.pageDown()) readOn() }
            else -> readOn()
        }
        event.key == Key.Spacebar && event.isShiftPressed -> scope.launch { articleScroll.pageUp() }
        event.key == Key.Enter && plain -> openSelectedArticle()
        event.key == Key.DirectionRight && plain -> cycleSelectedState(forward = true)
        event.key == Key.DirectionLeft && plain -> cycleSelectedState(forward = false)
        event.key == Key.Delete && plain -> deleteSelectedArticle()
        else -> return false
    }
    return true
}

// 1 回で送る量。読んでいた位置を見失わないよう、表示範囲より少し短くする。
private const val PAGE_FRACTION = 0.85f

/** 1 画面ぶん先へ送る。すでに末尾なら何もせず false を返す。 */
private suspend fun ScrollState.pageDown(): Boolean {
    if (value >= maxValue) return false
    scrollTo((value + viewportSize * PAGE_FRACTION).toInt().coerceAtMost(maxValue))
    return true
}

private suspend fun ScrollState.pageUp() {
    scrollTo((value - viewportSize * PAGE_FRACTION).toInt().coerceAtLeast(0))
}
