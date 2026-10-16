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
import kotlin.test.assertTrue

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

    private fun article(title: String, sortAt: Long = 0) =
        NewArticle(title, title, link = null, summary = null, content = null, author = null, category = null, publishedAt = null, sortAt = sortAt, fetchedAt = 0, state = ArticleState.UNREAD)

    private fun channelOf(vararg articles: NewArticle) =
        NewChannel("Example", "https://example.com/feed", null, null, paused = false, firstFetchAsRead = false, articles = articles.toList())

    /** 版 1 のデータベースを開くと移行が走り、state が 2 だった記事は既読になる。ほかの記事の状況は変わらない。 */
    @Test
    fun migratesVersion1ArticlesWithRemovedStateToRead() = runBlocking {
        storeOf(dataDir).importNodes(listOf(channelOf(article("kept"), article("unread"))))
        execute("UPDATE article SET state = 2 WHERE title = 'kept'")
        execute("ALTER TABLE article DROP COLUMN fetched_at")
        execute("PRAGMA user_version = 1")

        val migrated = storeOf(dataDir)

        assertEquals(LegirssDatabase.Schema.version, storedSchemaVersion())
        assertEquals(
            mapOf("kept" to ArticleState.READ, "unread" to ArticleState.UNREAD),
            migrated.loadArticleRows(channelIds = null).associate { it.title to it.state },
        )
    }

    /** 版 2 のデータベースを開くと移行が走り、記事の取得日には並べ替えに使う日時が入る。移行した時刻より先の日時は、移行した時刻に丸める。 */
    @Test
    fun migratesVersion2ArticlesFetchedTimeFromSortTime() = runBlocking {
        val past = 1_000_000L
        val farFuture = Long.MAX_VALUE / 2
        storeOf(dataDir).importNodes(listOf(channelOf(article("past", sortAt = past), article("future", sortAt = farFuture))))
        execute("ALTER TABLE article DROP COLUMN fetched_at")
        execute("PRAGMA user_version = 2")
        val beforeMigration = System.currentTimeMillis()

        val fetched = storeOf(dataDir).loadArticleRows(channelIds = null).associate { it.title to it.fetchedAt }

        assertEquals(past, fetched["past"])
        assertTrue(requireNotNull(fetched["future"]) in (beforeMigration - MILLIS_PER_SECOND)..System.currentTimeMillis())
    }

    private companion object {
        /** 移行の SQL は秒の単位で時刻を得るため、その分の切り捨てを許す。 */
        const val MILLIS_PER_SECOND = 1_000L
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
