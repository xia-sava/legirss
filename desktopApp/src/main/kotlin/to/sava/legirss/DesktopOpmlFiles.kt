package to.sava.legirss

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.sava.legirss.ui.OpmlFiles
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/** OS のファイルを選ぶ画面を使って、OPML のファイルを読み書きする。 */
internal class DesktopOpmlFiles(private val ioDispatcher: CoroutineDispatcher) : OpmlFiles {
    override suspend fun pick(): ByteArray? =
        choose(PICK_TITLE, FileDialog.LOAD, initialName = FILE_PATTERN)?.let { path -> withContext(ioDispatcher) { Files.readAllBytes(path) } }

    override suspend fun save(suggestedName: String, text: String): Boolean {
        val path = choose(SAVE_TITLE, FileDialog.SAVE, initialName = suggestedName) ?: return false
        withContext(ioDispatcher) { Files.writeString(path, text) }
        return true
    }

    /** ファイルを選ぶ画面を出し、選ばれた場所を返す。選ばずに閉じたら null。閉じるまで戻らない。 */
    private fun choose(title: String, mode: Int, initialName: String): Path? {
        val dialog = FileDialog(null as Frame?, title, mode).apply {
            file = initialName
            isVisible = true
        }
        return dialog.file?.let { Path.of(dialog.directory.orEmpty(), it) }
    }

    private companion object {
        const val PICK_TITLE = "OPML の読み込み"
        const val SAVE_TITLE = "OPML の書き出し"

        // 読み込むときに一覧へ出すファイルの種類。
        const val FILE_PATTERN = "*.opml;*.xml"
    }
}
