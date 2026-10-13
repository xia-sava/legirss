package to.sava.legirss

import java.io.IOException

/**
 * Windows へのログオン時にアプリを起動する登録。
 *
 * 現在の利用者の Run キーに、起動用の実行ファイルのパスを書く。
 *
 * @param launcherPath 起動用の実行ファイルのパス。配布物から起動していないときは null で、登録はできない。
 * @param runCommand コマンドを実行して終了コードを返す。
 */
class StartupRegistration(
    private val launcherPath: String?,
    private val runCommand: (List<String>) -> Int = ::runQuietly,
) {
    /** 登録の操作ができるか。 */
    val isAvailable: Boolean get() = launcherPath != null

    fun isRegistered(): Boolean = runCommand(listOf("reg", "query", RUN_KEY, "/v", VALUE_NAME)) == 0

    /** 登録する。成功したら true。 */
    fun register(): Boolean {
        val path = launcherPath ?: return false
        return runCommand(listOf("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", "\"$path\" $START_IN_TRAY_OPTION", "/f")) == 0
    }

    /** 登録を解除する。成功したら true。 */
    fun unregister(): Boolean = runCommand(listOf("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f")) == 0

    companion object {
        /** ウィンドウを出さず、タスクトレイにしまった状態で起動する指定。 */
        const val START_IN_TRAY_OPTION = "--tray"

        private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
        private const val VALUE_NAME = "legirss"

        // jpackage で作った起動用の実行ファイルが設定するシステムプロパティ。
        private const val LAUNCHER_PATH_PROPERTY = "jpackage.app-path"

        /** インストールしたアプリの起動用の実行ファイルの場所。そこから起動されていなければ null。 */
        fun launcherPath(): String? = System.getProperty(LAUNCHER_PATH_PROPERTY)

        fun forCurrentProcess(): StartupRegistration = StartupRegistration(launcherPath())

        private fun runQuietly(command: List<String>): Int =
            try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor()
            } catch (exception: IOException) {
                AppLog.warn("コマンドを実行できません: ${command.first()}", exception)
                COMMAND_NOT_RUN
            }

        private const val COMMAND_NOT_RUN = -1
    }
}
