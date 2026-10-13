package to.sava.legirss

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceTest {
    private val dataDir = Files.createTempDirectory("legirss-single-instance-test")

    /** 最初の起動は成立し、同じ置き場所での 2 つ目の起動は成立しない。2 つ目からの合図は最初の側に届く。 */
    @Test
    fun rejectsSecondInstanceAndSignalsFirst() {
        val activated = CountDownLatch(1)

        val first = SingleInstance.acquire(dataDir) { activated.countDown() }
        val second = SingleInstance.acquire(dataDir) {}

        first.use {
            assertNotNull(first)
            assertNull(second)
            assertTrue(activated.await(5, TimeUnit.SECONDS), "合図が届かない")
        }
    }

    /** 呼び出し側が戻り値を保持していなくても、ガベージコレクションの後までロックを握り続ける。 */
    @Test
    fun keepsLockAfterGarbageCollectionWithoutReference() {
        val heldPort = acquireWithoutKeepingReference()
        repeat(5) {
            System.gc()
            Thread.sleep(100)
        }

        val second = SingleInstance.acquire(dataDir) {}

        second.use { assertNull(second) }
        assertTrue(heldPort > 0)
    }

    /** 戻り値を捨てて、握ったことだけを確かめる。待ち受けのポート番号を返す。 */
    private fun acquireWithoutKeepingReference(): Int {
        assertNotNull(SingleInstance.acquire(dataDir) {})
        return Files.readString(dataDir.resolve("legirss.port")).trim().toInt()
    }

    /** 先に起動した側が終われば、次の起動が成立する。 */
    @Test
    fun allowsNextInstanceAfterFirstCloses() {
        SingleInstance.acquire(dataDir) {}?.close()

        val next = SingleInstance.acquire(dataDir) {}

        next.use { assertNotNull(next) }
    }
}
