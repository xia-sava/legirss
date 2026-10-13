package to.sava.legirss

import java.io.IOException
import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** アプリの動作の記録。失敗の内容を、あとから確かめられるように残す。 */
internal object AppLog {
    private const val FILE_NAME = "legirss.log"
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
        val pattern = dataDir.resolve(FILE_NAME).toString().replace("%", "%%")
        try {
            logger.addHandler(FileHandler(pattern, FILE_SIZE_LIMIT_BYTES, FILE_COUNT, true).apply { formatter = SimpleFormatter() })
        } catch (exception: IOException) {
            warn("記録用のファイルを開けません: $pattern", exception)
        }
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
