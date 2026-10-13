package to.sava.legirss.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdaterTest {
    private val signer = ManifestSigner()

    /** 配布元に置かれている版。null なら、マニフェストを取得できないものとして扱う。 */
    private var publishedVersion: String? = "0.2.0"

    /** null でなければ、配布物を取得しようとしたときにこれを投げる。 */
    private var downloadFailure: IOException? = null
    private val launched = mutableListOf<Path>()
    private val reportedErrors = mutableListOf<String>()
    private var exitRequests = 0

    private fun TestScope.updater(): AppUpdater {
        val checker = UpdateChecker(version("0.1.0"), signer.publicKey) { url ->
            val manifest = publishedVersion?.let(::manifestOf) ?: return@UpdateChecker null
            if (url == UpdateSource.MANIFEST_URL) manifest else signer.sign(manifest).encodeToByteArray()
        }
        return AppUpdater(
            checker = checker,
            canInstall = true,
            download = { downloadFailure?.let { throw it } ?: Path.of("downloaded.msi") },
            launch = launched::add,
            scope = backgroundScope,
            reportError = { message, _ -> reportedErrors.add(message) },
            onReadyToExit = { exitRequests += 1 },
        )
    }

    /** 確かめて新しい版があれば、その版を入れられる状況になる。新しい版が無くなれば元に戻る。 */
    @Test
    fun becomesAvailableWhenNewerVersionIsPublished() = runTest(UnconfinedTestDispatcher()) {
        val updater = updater()

        updater.check()
        val afterNewer = updater.state.value
        publishedVersion = "0.1.0"
        updater.check()

        assertEquals("0.2.0", assertIs<UpdateState.Available>(afterNewer).release.version.toString())
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    /** 確かめられなかったときは、分かっている状況を変えない。 */
    @Test
    fun keepsStateWhenCheckFails() = runTest(UnconfinedTestDispatcher()) {
        val updater = updater()
        updater.check()
        publishedVersion = null

        updater.check()

        assertIs<UpdateState.Available>(updater.state.value)
    }

    /** 入れると、取得した配布物を外へ引き渡してから、アプリの終了を求める。 */
    @Test
    fun handsOffInstallerAndRequestsExit() = runTest(UnconfinedTestDispatcher()) {
        val updater = updater()
        updater.check()

        updater.install()

        assertEquals(listOf(Path.of("downloaded.msi")), launched)
        assertEquals(1, exitRequests)
        assertIs<UpdateState.Installing>(updater.state.value)
    }

    /** 配布物を取得できなければ、理由を記録して失敗の状況になり、アプリの終了は求めない。もう一度入れ直せる。 */
    @Test
    fun reportsFailureAndAllowsRetry() = runTest(UnconfinedTestDispatcher()) {
        val updater = updater()
        updater.check()
        downloadFailure = IOException("unreachable")

        updater.install()
        val afterFailure = updater.state.value
        downloadFailure = null
        updater.install()

        assertIs<UpdateState.Failed>(afterFailure)
        assertEquals(listOf("新しい版を入れられません"), reportedErrors)
        assertEquals(1, exitRequests)
        assertEquals(1, launched.size)
    }

    /** 新しい版が見つかっていないうちは、入れようとしても何もしない。 */
    @Test
    fun doesNothingWithoutAvailableVersion() = runTest(UnconfinedTestDispatcher()) {
        val updater = updater()

        updater.install()

        assertEquals(UpdateState.Idle, updater.state.value)
        assertEquals(0, exitRequests)
    }
}
