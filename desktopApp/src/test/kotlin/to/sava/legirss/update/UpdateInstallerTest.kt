package to.sava.legirss.update

import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateInstallerTest {
    private val content = "installer bytes".encodeToByteArray()
    private val downloadedTo = mutableListOf<Path>()

    /** このテストが作るファイルを全て置く場所。テストが終わったら消す。 */
    private val workDir: Path = Files.createTempDirectory("legirss-installer-test")
    private val downloadRoot: Path = Files.createDirectory(workDir.resolve("downloads"))

    @AfterTest
    fun removeWorkDir() {
        workDir.toFile().deleteRecursively()
    }

    private fun installer(launcherPath: String? = """C:\Apps\legirss\legirss.exe""") = UpdateInstaller(
        launcherPath = launcherPath,
        logDir = workDir,
        downloadRoot = downloadRoot,
        download = { _, target ->
            downloadedTo.add(target)
            Files.write(target, content)
        },
    )

    private fun release(sha256: String) = UpdateRelease(version("0.2.0"), sha256)

    private fun fileOf(name: String, bytes: ByteArray): Path = Files.write(workDir.resolve(name), bytes)

    /** ファイルの SHA-256 を 16 進の小文字で求める。 */
    @Test
    fun computesSha256OfFile() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Of(fileOf("abc.bin", "abc".encodeToByteArray())))
    }

    /** 取得した配布物の照合値がマニフェストと合えば、置いた場所を返す。 */
    @Test
    fun returnsDownloadedInstallerWhenDigestMatches() = runTest {
        val expected = sha256Of(fileOf("expected.bin", content))

        val installed = installer().downloadVerified(release(expected))

        assertEquals(content.toList(), Files.readAllBytes(installed).toList())
    }

    /** 前に取得した配布物は、置いたディレクトリごと消す。同じ場所にある、ほかのディレクトリやファイルには触れない。 */
    @Test
    fun removesOldDownloadsOnly() = runTest {
        val installer = installer()
        val downloaded = installer.downloadVerified(release(sha256Of(fileOf("expected.bin", content))))
        Files.writeString(downloaded.resolveSibling("legirss-apply-update.ps1"), "script")
        val unrelatedDir = Files.createDirectory(downloadRoot.resolve("legirss-update-notes"))
        val unrelatedFile = Files.writeString(downloadRoot.resolve("legirss-update123"), "not a directory")

        installer.removeOldDownloads()

        assertFalse(Files.exists(downloaded.parent))
        assertTrue(Files.exists(unrelatedDir))
        assertTrue(Files.exists(unrelatedFile))
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
