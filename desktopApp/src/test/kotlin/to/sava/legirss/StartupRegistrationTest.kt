package to.sava.legirss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupRegistrationTest {
    private val commands = mutableListOf<List<String>>()
    private var exitCode = 0

    private fun registration(launcherPath: String?) = StartupRegistration(launcherPath) { command ->
        commands.add(command)
        exitCode
    }

    /** 登録では、起動用の実行ファイルをタスクトレイにしまった状態で起動するコマンドを Run キーに書く。 */
    @Test
    fun registersLauncherWithTrayOption() {
        val registered = registration("""C:\Apps\legirss\legirss.exe""").register()

        assertTrue(registered)
        assertEquals(
            listOf(
                "reg", "add", """HKCU\Software\Microsoft\Windows\CurrentVersion\Run""", "/v", "legirss",
                "/t", "REG_SZ", "/d", "\"C:\\Apps\\legirss\\legirss.exe\" --tray", "/f",
            ),
            commands.single(),
        )
    }

    /** 起動用の実行ファイルが分からないときは登録の操作ができず、コマンドも実行しない。 */
    @Test
    fun cannotRegisterWithoutLauncher() {
        val registration = registration(launcherPath = null)

        assertFalse(registration.isAvailable)
        assertFalse(registration.register())
        assertTrue(commands.isEmpty())
    }

    /** 登録の有無は問い合わせのコマンドの終了コードで判断し、解除は削除のコマンドの成否を返す。 */
    @Test
    fun reportsStateFromExitCodes() {
        val registration = registration("""C:\Apps\legirss\legirss.exe""")

        val registeredOnSuccess = registration.isRegistered()
        val unregisteredOnSuccess = registration.unregister()
        exitCode = 1
        val registeredOnFailure = registration.isRegistered()

        assertTrue(registeredOnSuccess)
        assertTrue(unregisteredOnSuccess)
        assertFalse(registeredOnFailure)
        assertEquals(listOf("query", "delete", "query"), commands.map { it[1] })
    }
}
