package to.sava.legirss.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import to.sava.legirss.model.NewGroup
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseBackupTest {
    private val dataDir: Path = Files.createTempDirectory("legirss-backup-test")
    private val store = LegirssStore(DatabaseFactory.open(dataDir), Dispatchers.IO)
    private var today = LocalDate.of(2026, 3, 1)
    private val backup = DatabaseBackup(dataDir, store) { today }

    private fun backupNames() = dataDir.resolve("backup").listDirectoryEntries().map { it.name }.sorted()

    /** 複製は、作った時点の内容を持つ、単独で開けるデータベースになる。 */
    @Test
    fun createsCopyThatHoldsCurrentContent() = runBlocking {
        store.importNodes(listOf(NewGroup(name = "News", children = emptyList())))

        val created = backup.backUpIfDue()
        store.importNodes(listOf(NewGroup(name = "Added later", children = emptyList())))

        val copyDir = Files.createTempDirectory("legirss-backup-copy")
        Files.copy(dataDir.resolve("backup").resolve("legirss-2026-03-01.db"), copyDir.resolve("legirss.db"))
        val copy = LegirssStore(DatabaseFactory.open(copyDir), Dispatchers.IO)
        assertTrue(created)
        assertEquals(listOf("News"), copy.loadTree().map { it.name })
    }

    /** 同じ日に 2 度目を頼んでも、新しい複製は作らない。 */
    @Test
    fun createsOnlyOneCopyPerDay() = runBlocking {
        backup.backUpIfDue()

        val createdAgain = backup.backUpIfDue()

        assertFalse(createdAgain)
        assertEquals(listOf("legirss-2026-03-01.db"), backupNames())
    }

    /** 複製は新しい方から 7 個を残し、それより古いものを消す。 */
    @Test
    fun keepsSevenNewestCopies() = runBlocking {
        repeat(9) {
            backup.backUpIfDue()
            today = today.plusDays(1)
        }

        assertEquals((3..9).map { "legirss-2026-03-0$it.db" }, backupNames())
    }
}
