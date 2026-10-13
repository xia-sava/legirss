package to.sava.legirss.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import to.sava.legirss.data.db.LegirssDatabase
import to.sava.legirss.model.ArticleState
import to.sava.legirss.model.NewArticle
import to.sava.legirss.model.NewChannel
import to.sava.legirss.model.NewGroup
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabaseFactoryTest {
    private val dataDir: Path = Files.createTempDirectory("legirss-factory-test")

    private fun storeOf(directory: Path) = LegirssStore(DatabaseFactory.open(directory), Dispatchers.IO)

    private fun storedSchemaVersion(): Long =
        DriverManager.getConnection(DatabaseFactory.urlOf(dataDir)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    /** 作ったデータベースには、いまのスキーマの版が記録される。開き直しても作り直さず、中身が残る。 */
    @Test
    fun recordsSchemaVersionAndKeepsDataWhenReopened() = runBlocking {
        storeOf(dataDir).importNodes(listOf(NewGroup(name = "News", children = emptyList())))

        val reopened = storeOf(dataDir)

        assertEquals(LegirssDatabase.Schema.version, storedSchemaVersion())
        assertEquals(listOf("News"), reopened.loadTree().map { it.name })
    }

    private fun execute(sql: String) {
        DriverManager.getConnection(DatabaseFactory.urlOf(dataDir)).use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    /** 版 1 のデータベースを開くと移行が走り、state が 2 だった記事は既読になる。ほかの記事の状況は変わらない。 */
    @Test
    fun migratesVersion1ArticlesWithRemovedStateToRead() = runBlocking {
        fun article(title: String) =
            NewArticle(title, title, link = null, summary = null, content = null, author = null, category = null, publishedAt = null, sortAt = 0, state = ArticleState.UNREAD)
        val channel = NewChannel("Example", "https://example.com/feed", null, null, paused = false, firstFetchAsRead = false, articles = listOf(article("kept"), article("unread")))
        storeOf(dataDir).importNodes(listOf(channel))
        execute("UPDATE article SET state = 2 WHERE title = 'kept'")
        execute("PRAGMA user_version = 1")

        val migrated = storeOf(dataDir)

        assertEquals(LegirssDatabase.Schema.version, storedSchemaVersion())
        assertEquals(
            mapOf("kept" to ArticleState.READ, "unread" to ArticleState.UNREAD),
            migrated.loadArticleRows(channelIds = null).associate { it.title to it.state },
        )
    }

    /** スキーマの版が古いデータベースは、移行の前に、その時点の中身を持つ複製を版の名前で残す。 */
    @Test
    fun backsUpDatabaseBeforeMigration() = runBlocking {
        storeOf(dataDir).importNodes(listOf(NewGroup(name = "News", children = emptyList())))
        val newerSchemaVersion = LegirssDatabase.Schema.version + 1

        val copy = DatabaseFactory.backUpBeforeMigration(dataDir, newerSchemaVersion)

        assertEquals("legirss-before-migration-v${LegirssDatabase.Schema.version}.db", copy?.name)
        val copyDir = Files.createTempDirectory("legirss-factory-test-copy")
        Files.copy(requireNotNull(copy), copyDir.resolve("legirss.db"))
        assertEquals(listOf("News"), storeOf(copyDir).loadTree().map { it.name })
    }

    /** 版が古くないデータベース、まだ無いデータベース、その版の複製が既にあるデータベースでは、複製を作らない。 */
    @Test
    fun skipsBackupWhenNotNeeded() = runBlocking {
        val currentVersion = LegirssDatabase.Schema.version
        val withoutDatabase = DatabaseFactory.backUpBeforeMigration(dataDir, currentVersion + 1)
        storeOf(dataDir)

        val upToDate = DatabaseFactory.backUpBeforeMigration(dataDir, currentVersion)
        DatabaseFactory.backUpBeforeMigration(dataDir, currentVersion + 1)
        val alreadyBackedUp = DatabaseFactory.backUpBeforeMigration(dataDir, currentVersion + 1)

        assertNull(withoutDatabase)
        assertNull(upToDate)
        assertNull(alreadyBackedUp)
    }
}
