package to.sava.legirss.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name

private const val DOWNLOAD_DIR_PREFIX = "legirss-update"
private const val INSTALLER_FILE_NAME = "legirss-update.msi"
private const val APPLY_SCRIPT_NAME = "legirss-apply-update.ps1"
private const val APPLY_LOG_NAME = "update-apply.log"

// 適用のスクリプトが、アプリの終了を待つ上限（秒）。
private const val EXIT_WAIT_SECONDS = 60

private const val POWERSHELL_RELATIVE_PATH = """System32\WindowsPowerShell\v1.0\powershell.exe"""
private const val MSIEXEC_RELATIVE_PATH = """System32\msiexec.exe"""
private const val DIGEST_ALGORITHM = "SHA-256"

/** PowerShell の、中身を解釈しない文字列にする。 */
private fun powerShellLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

/**
 * 配布物を入れるスクリプトの中身を作る。
 *
 * タスクトレイに常駐するアプリは、インストーラからは止められない。そこで入れる作業はアプリの外へ出し、
 * [waitPids] のプロセスが終わるのを待ってからインストーラを画面なしで走らせ、済んだら [launcherPath] を起動する。
 * インストーラの終了コードは [resultLogPath] に書き残す。入れている間はアプリが動いていないので、成否を知るのはこのファイルだけになる。
 */
internal fun applyUpdateScript(
    installerPath: String,
    launcherPath: String,
    resultLogPath: String,
    waitPids: List<Long>,
    waitSeconds: Int = EXIT_WAIT_SECONDS,
): String {
    val pids = waitPids.joinToString(",")
    return """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}log = ${powerShellLiteral(resultLogPath)}
        foreach (${'$'}id in @($pids)) { Wait-Process -Id ${'$'}id -Timeout $waitSeconds -ErrorAction SilentlyContinue }
        ${'$'}msiexec = Join-Path ${'$'}env:SystemRoot ${powerShellLiteral(MSIEXEC_RELATIVE_PATH)}
        try {
            ${'$'}installer = Start-Process ${'$'}msiexec -ArgumentList '/i', ${powerShellLiteral("\"$installerPath\"")}, '/qn' -Wait -PassThru
            ${'$'}code = ${'$'}installer.ExitCode
        } catch {
            Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) msiexec could not be started: ${'$'}_"
            exit 1
        }
        if (${'$'}code -ne 0) {
            Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) msiexec failed with exit code ${'$'}code"
            exit ${'$'}code
        }
        Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) msiexec succeeded"
        Start-Process ${powerShellLiteral(launcherPath)}
    """.trimIndent() + "\n"
}

/** ファイルの SHA-256 を、16 進の小文字で返す。 */
internal fun sha256Of(file: Path): String {
    val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
    Files.newInputStream(file).use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        generateSequence { stream.read(buffer).takeIf { it >= 0 } }.forEach { digest.update(buffer, 0, it) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 新しい版の配布物を取得して、アプリの外で走るスクリプトへ引き渡す。
 *
 * @param launcherPath インストールされているアプリの実行ファイル。配布物として動いていなければ null で、その場合は入れられない。
 * @param logDir 入れた結果を書き残すファイルを置く場所。
 * @param download URL の中身をファイルへ保存する。保存できなければ [IOException] を投げる。
 */
internal class UpdateInstaller(
    private val launcherPath: String?,
    private val logDir: Path,
    private val download: suspend (url: String, target: Path) -> Unit,
) {
    /** 新しい版を入れられる動かし方をしているか。 */
    val isSupported: Boolean get() = !launcherPath.isNullOrBlank()

    /**
     * [release] の配布物を取得し、照合値が合うことを確かめて返す。
     *
     * 置き場所は取得のたびに作り、確かめた配布物をほかのプロセスが差し替える隙を狭める。
     *
     * @throws IOException 取得できない。または、取得したものの照合値が合わない。
     */
    suspend fun downloadVerified(release: UpdateRelease): Path {
        val installer = Files.createTempDirectory(DOWNLOAD_DIR_PREFIX).resolve(INSTALLER_FILE_NAME)
        try {
            download(UpdateSource.INSTALLER_URL, installer)
            if (sha256Of(installer) != release.sha256) throw IOException("取得した配布物の照合値が合いません")
        } catch (exception: IOException) {
            discard(installer)
            throw exception
        }
        return installer
    }

    /**
     * [installer] を入れるスクリプトを起動する。スクリプトはこのアプリの終了を待つので、呼び出した側はこのあとアプリを終了させる。
     *
     * @throws IOException スクリプトを書けない、起動できない。
     */
    fun launch(installer: Path) {
        val launcher = launcherPath?.takeIf { it.isNotBlank() } ?: throw IOException("アプリの実行ファイルの場所が分かりません")
        val script = installer.resolveSibling(APPLY_SCRIPT_NAME)
        Files.writeString(
            script,
            applyUpdateScript(installer.toString(), launcher, logDir.resolve(APPLY_LOG_NAME).toString(), ownProcessIds()),
        )
        ProcessBuilder(
            powerShellPath(),
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-File", script.toString(),
        ).start()
    }

    /** 取得した配布物と、それを置いた場所を捨てる。 */
    private fun discard(installer: Path) {
        installer.deleteIfExists()
        installer.parent?.takeIf { it.name.startsWith(DOWNLOAD_DIR_PREFIX) }?.deleteIfExists()
    }

    /** PowerShell の本体。探す場所を PATH に任せず、%SystemRoot% から組み立てる。 */
    private fun powerShellPath(): String =
        System.getenv("SystemRoot")
            ?.let { Path.of(it, POWERSHELL_RELATIVE_PATH) }
            ?.takeIf(Files::isRegularFile)
            ?.toString()
            ?: throw IOException("PowerShell が見つかりません")

    /** スクリプトが終了を待つプロセス。実行ファイルをつかんでいる、このプロセスと、これを起動したプロセス。 */
    private fun ownProcessIds(): List<Long> =
        ProcessHandle.current().let { current -> listOfNotNull(current.pid(), current.parent().orElse(null)?.pid()) }
}
