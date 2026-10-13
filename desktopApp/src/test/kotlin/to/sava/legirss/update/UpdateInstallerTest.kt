package to.sava.legirss.update

import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateInstallerTest {
    private val content = "installer bytes".encodeToByteArray()
    private val downloadedTo = mutableListOf<Path>()

    private fun installer(launcherPath: String? = """C:\Apps\legirss\legirss.exe""") = UpdateInstaller(
        launcherPath = launcherPath,
        logDir = Files.createTempDirectory("legirss-update-test-log"),
        download = { _, target ->
            downloadedTo.add(target)
            Files.write(target, content)
        },
    )

    private fun release(sha256: String) = UpdateRelease(version("0.2.0"), sha256)

    /** ファイルの SHA-256 を 16 進の小文字で求める。 */
    @Test
    fun computesSha256OfFile() {
        val file = Files.createTempFile("legirss-update-test", ".bin").also { Files.write(it, "abc".encodeToByteArray()) }

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Of(file))
    }

    /** 取得した配布物の照合値が知らせと合えば、置いた場所を返す。 */
    @Test
    fun returnsDownloadedInstallerWhenDigestMatches() = runTest {
        val expected = sha256Of(Files.createTempFile("legirss-update-test", ".bin").also { Files.write(it, content) })

        val installed = installer().downloadVerified(release(expected))

        assertEquals(content.toList(), Files.readAllBytes(installed).toList())
    }

    /** 照合値が合わない配布物は受け付けず、取得したファイルを残さない。 */
    @Test
    fun rejectsAndRemovesInstallerWhenDigestDiffers() = runTest {
        assertFailsWith<IOException> { installer().downloadVerified(release(SAMPLE_SHA256)) }

        assertFalse(Files.exists(downloadedTo.single()))
        assertFalse(Files.exists(downloadedTo.single().parent))
    }

    /** 実行ファイルの場所が分からない動かし方では、新しい版を入れられないと答える。 */
    @Test
    fun isUnsupportedWithoutLauncherPath() {
        assertTrue(installer().isSupported)
        assertFalse(installer(launcherPath = null).isSupported)
    }

    /** 入れるスクリプトは、アプリの終了を待ち、配布物を画面なしで入れ、結果を書き残してから、アプリを起動し直す。 */
    @Test
    fun buildsScriptThatWaitsInstallsAndRelaunches() {
        val script = applyUpdateScript(
            installerPath = """C:\Temp\it's here\legirss-update.msi""",
            launcherPath = """C:\Apps\legirss\legirss.exe""",
            resultLogPath = """C:\Data\update-apply.log""",
            waitPids = listOf(111, 222),
            waitSeconds = 30,
        )

        assertTrue("foreach (\$id in @(111,222)) { Wait-Process -Id \$id -Timeout 30" in script)
        // パスに含まれる引用符は重ねて、文字列の終わりと取り違えられないようにする。
        assertTrue("""'/i', '"C:\Temp\it''s here\legirss-update.msi"', '/qn'""" in script)
        assertTrue("""${'$'}log = 'C:\Data\update-apply.log'""" in script)
        assertTrue(script.trimEnd().endsWith("""Start-Process 'C:\Apps\legirss\legirss.exe'"""))
    }
}
