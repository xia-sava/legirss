package to.sava.legirss

import java.io.IOException
import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** アプリの動作の記録。失敗の内容と動作の経過を、あとから確かめられるように残す。 */
internal object AppLog {
    private const val FILE_NAME_PATTERN = "legirss.%g.log"
    private const val FILE_SIZE_LIMIT_BYTES = 1024 * 1024
    private const val FILE_COUNT = 2

    private val logger: Logger = Logger.getLogger("to.sava.legirss")

    /**
     * 以後の記録を [dataDir] の下のファイルにも書く。
     *
     * ファイルは [FILE_SIZE_LIMIT_BYTES] を超えると切り替わり、[FILE_COUNT] 個までを残す。
     */
    fun writeTo(dataDir: Path) {
        // パターンの中の % は置き換えの記号として扱われるので、パスに含まれる分は重ねて書く。
        // ファイルの名前に入れた %g は、切り替えた世代の番号に置き換わる。
        val pattern = dataDir.toString().replace("%", "%%") + "/" + FILE_NAME_PATTERN
        try {
            logger.addHandler(FileHandler(pattern, FILE_SIZE_LIMIT_BYTES, FILE_COUNT, true).apply { formatter = SimpleFormatter() })
        } catch (exception: IOException) {
            warn("記録用のファイルを開けません: $pattern", exception)
        }
    }

    /** 動作の経過を記録する。 */
    fun info(message: String) {
        logger.log(Level.INFO, message)
    }

    /** 動作は続けられる失敗を記録する。 */
    fun warn(message: String, cause: Throwable) {
        logger.log(Level.WARNING, message, cause)
    }

    /** 想定していない失敗を記録する。 */
    fun error(message: String, cause: Throwable) {
        logger.log(Level.SEVERE, message, cause)
    }
}
