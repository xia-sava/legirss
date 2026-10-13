package to.sava.legirss.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import to.sava.legirss.data.db.LegirssDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object DatabaseFactory {
    private const val DATABASE_FILE_NAME = "legirss.db"

    /** [dataDir] の下のデータベースを開く。無ければ作る。 */
    fun open(dataDir: Path): LegirssDatabase {
        Files.createDirectories(dataDir)
        return open("jdbc:sqlite:${dataDir.resolve(DATABASE_FILE_NAME)}")
    }

    fun openInMemory(): LegirssDatabase = open(JdbcSqliteDriver.IN_MEMORY)

    private fun open(url: String): LegirssDatabase {
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        return LegirssDatabase(JdbcSqliteDriver(url, properties, LegirssDatabase.Schema))
    }
}
