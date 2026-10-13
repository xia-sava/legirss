package to.sava.legirss.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import to.sava.legirss.data.db.LegirssDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

object DatabaseFactory {
    private const val DATABASE_FILE_NAME = "legirss.db"
    private const val BUSY_TIMEOUT_MILLIS = 5_000
    private const val BACKUP_DIR_NAME = "backup"
    private const val PRE_MIGRATION_PREFIX = "legirss-before-migration-v"
    private const val PRE_MIGRATION_SUFFIX = ".db"

    /**
     * [dataDir] の下のデータベースを開く。無ければ作る。
     *
     * 古い版のアプリが作ったデータベースなら、いまのスキーマへ移行する。移行の前に、元の状態の複製を残す。
     *
     * @throws java.sql.SQLException 複製を作れない、移行できない、開けない。
     */
    fun open(dataDir: Path): LegirssDatabase {
        Files.createDirectories(dataDir)
        backUpBeforeMigration(dataDir, LegirssDatabase.Schema.version)
        return open(urlOf(dataDir))
    }

    /** [dataDir] の下のデータベースへ接続するための JDBC の URL。 */
    internal fun urlOf(dataDir: Path): String = "jdbc:sqlite:${dataDir.resolve(DATABASE_FILE_NAME)}"

    /** [dataDir] のデータベースの複製を置くディレクトリ。 */
    internal fun backupDirOf(dataDir: Path): Path = dataDir.resolve(BACKUP_DIR_NAME)

    /** [dataDir] の下のデータベースを、単独で開ける形で [target] へ複製する。 */
    internal fun copyTo(dataDir: Path, target: Path) {
        DriverManager.getConnection(urlOf(dataDir)).use { connection ->
            connection.prepareStatement("VACUUM INTO ?").use { statement ->
                statement.setString(1, target.toString())
                statement.execute()
            }
        }
    }

    /**
     * データベースのスキーマの版が [schemaVersion] より古ければ、移行する前の状態を複製して残す。
     *
     * 複製は版ごとに 1 つで、日ごとのバックアップとは別に、消さずに置いておく。
     * データベースがまだ無いとき、版が古くないとき、その版の複製が既にあるときは何もしない。
     *
     * @return 複製を作ったら、その場所。作らなければ null。
     */
    internal fun backUpBeforeMigration(dataDir: Path, schemaVersion: Long): Path? {
        if (!Files.exists(dataDir.resolve(DATABASE_FILE_NAME))) return null
        val storedVersion = storedSchemaVersionOf(dataDir).takeIf { it in 1 until schemaVersion } ?: return null
        val target = backupDirOf(dataDir).resolve("$PRE_MIGRATION_PREFIX$storedVersion$PRE_MIGRATION_SUFFIX")
        if (Files.exists(target)) return null
        Files.createDirectories(target.parent)
        copyTo(dataDir, target)
        return target
    }

    /** データベースのファイルに記録されている、スキーマの版。スキーマを作る前のファイルなら 0。 */
    private fun storedSchemaVersionOf(dataDir: Path): Long =
        DriverManager.getConnection(urlOf(dataDir)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result -> if (result.next()) result.getLong(1) else 0L }
            }
        }

    fun openInMemory(): LegirssDatabase = open(JdbcSqliteDriver.IN_MEMORY)

    private fun open(url: String): LegirssDatabase {
        val properties = Properties().apply {
            setProperty("foreign_keys", "true")
            // 別のプロセスがファイルを使っている間は、すぐに失敗させずに待つ。
            setProperty("busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
            // トランザクションは始めた時点で書き込みのロックを取る。読んでから書く段階でロックを
            // 取りにいくと、ほかの書き込みと重なったときに待たずに失敗する。
            setProperty("transaction_mode", "IMMEDIATE")
        }
        return LegirssDatabase(JdbcSqliteDriver(url, properties, LegirssDatabase.Schema))
    }
}
