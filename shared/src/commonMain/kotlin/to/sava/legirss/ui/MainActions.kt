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
import to.sava.legirss.model.AppSettings
import to.sava.legirss.model.ArticleState
import to.sava.legirss.ui.component.TreeDropPlace

/** メイン画面から呼ぶ操作。 */
interface MainActions {
    fun selectNode(selection: NodeSelection)

    fun toggleNode(selection: NodeSelection)

    fun setFilter(filter: StateFilter)

    fun cycleFilter()

    fun sortBy(column: ArticleColumn)

    fun selectArticle(index: Int)

    fun markArticlesTo(index: Int)

    fun toggleArticleMark(index: Int)

    fun markAllArticles()

    fun readOn()

    fun toggleSelectedState()

    fun setSelectedState(state: ArticleState)

    fun deleteSelectedArticles()

    fun openSelectedArticle()

    fun copySelected(content: CopyContent)

    fun openLink(url: String)

    fun updateAll()

    fun updateSelected()

    fun updateNode(selection: NodeSelection)

    fun setNodePaused(selection: NodeSelection, paused: Boolean)

    fun reloadList()

    fun openAddChannel()

    fun submitAddChannel(url: String)

    fun openAddGroup()

    fun openRename(selection: NodeSelection)

    fun openChangeFeedUrl(selection: NodeSelection)

    fun submitText(text: String)

    fun requestDelete(selection: NodeSelection)

    fun confirmDelete()

    fun sortChildrenByName(selection: NodeSelection)

    fun moveNode(source: NodeSelection, target: NodeSelection, place: TreeDropPlace)

    fun importOpml()

    fun exportOpml(selection: NodeSelection)

    fun openSettings()

    fun submitSettings(settings: AppSettings)

    fun closeDialog()
}

/**
 * メイン画面のキー操作のうち、メニューの項目に載せないもの。
 *
 * キー操作はメニューの項目のショートカットとして受ける。項目に付けられるショートカットは 1 つなので、
 * 同じ操作に割り当てた 2 つ目のキーをここで受ける。一覧の中の移動（↑↓ など）は一覧が自分で処理するので、ここには来ない。
 *
 * @return 処理したら true。
 */
fun MainActions.handleKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
    if (event.key != Key.DirectionLeft || !plain) return false
    toggleSelectedState()
    return true
}

/** 記事を選んでいれば本文を先へ送り、末尾まで読んでいたら次の未読へ進む。 */
fun MainActions.readNextPage(hasSelectedArticle: Boolean, articleScroll: ScrollState, scope: CoroutineScope) {
    if (!hasSelectedArticle) {
        readOn()
        return
    }
    scope.launch { if (!articleScroll.pageDown()) readOn() }
}

/** 記事の本文を 1 画面ぶん前へ戻す。 */
fun pageBack(articleScroll: ScrollState, scope: CoroutineScope) {
    scope.launch { articleScroll.pageUp() }
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
