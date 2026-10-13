package to.sava.legirss.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * データベースの複製を、1 日に 1 つ、データの置き場所の下の `backup` に作る。
 *
 * 複製の名前には日付を付け、新しい方から [GENERATIONS] 個を残す。
 */
class DatabaseBackup(
    private val dataDir: Path,
    private val store: LegirssStore,
    private val today: () -> LocalDate,
) {
    private val directory = DatabaseFactory.backupDirOf(dataDir)

    /**
     * 今日の複製がまだ無ければ作り、古い複製を消す。
     *
     * @return 複製を作ったら true。
     * @throws java.io.IOException ファイルを作れない、消せないなど。
     * @throws java.sql.SQLException データベースを複製できない。
     */
    suspend fun backUpIfDue(): Boolean = store.withExclusiveAccess {
        val target = directory.resolve("$FILE_PREFIX${today()}$FILE_SUFFIX")
        if (Files.exists(target)) return@withExclusiveAccess false
        Files.createDirectories(directory)
        // 書き終えてから名前を付け替えるので、途中で止まっても壊れた複製は今日の分として残らない。
        val unfinished = directory.resolve("${target.name}$UNFINISHED_SUFFIX")
        Files.deleteIfExists(unfinished)
        DatabaseFactory.copyTo(dataDir, unfinished)
        Files.move(unfinished, target, StandardCopyOption.ATOMIC_MOVE)
        removeOldGenerations()
        true
    }

    private fun removeOldGenerations() {
        directory.listDirectoryEntries()
            .filter { BACKUP_NAME.matches(it.name) }
            .sortedByDescending { it.name }
            .drop(GENERATIONS)
            .forEach(Files::delete)
    }

    companion object {
        const val GENERATIONS = 7

        private const val FILE_PREFIX = "legirss-"
        private const val FILE_SUFFIX = ".db"
        private const val UNFINISHED_SUFFIX = ".tmp"
        private val BACKUP_NAME = Regex("""legirss-\d{4}-\d{2}-\d{2}\.db""")
    }
}
