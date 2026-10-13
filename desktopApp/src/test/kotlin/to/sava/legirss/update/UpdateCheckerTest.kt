package to.sava.legirss.update

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateCheckerTest {
    private val signer = ManifestSigner()

    /** 配布元に置かれている中身。URL ごとに持ち、無い URL は取得できないものとして扱う。 */
    private val published = mutableMapOf<String, ByteArray>()

    private fun publish(manifest: ByteArray, signature: String? = signer.sign(manifest)) {
        published[UpdateSource.MANIFEST_URL] = manifest
        signature?.let { published[UpdateSource.SIGNATURE_URL] = it.encodeToByteArray() }
    }

    private suspend fun check(currentVersion: String): UpdateCheck =
        UpdateChecker(version(currentVersion), signer.publicKey, fetch = { url -> published[url] }).check()

    /** 配布中の版が動いている版より新しければ、その版と照合値を知らせる。 */
    @Test
    fun reportsNewerVersion() = runTest {
        publish(manifestOf("0.2.0"))

        val result = check(currentVersion = "0.1.0")

        val release = assertIs<UpdateCheck.Available>(result).release
        assertEquals("0.2.0", release.version.toString())
        assertEquals(SAMPLE_SHA256, release.sha256)
    }

    /** 配布中の版が動いている版と同じか古ければ、新しい版は無いものとして扱う。 */
    @Test
    fun reportsUpToDateForSameOrOlderVersion() = runTest {
        publish(manifestOf("0.2.0"))

        assertEquals(UpdateCheck.UpToDate, check(currentVersion = "0.2.0"))
        assertEquals(UpdateCheck.UpToDate, check(currentVersion = "0.3.0"))
    }

    /** 署名が置かれていないマニフェスト、署名の合わないマニフェストは、新しい版が書かれていても受け付けない。 */
    @Test
    fun rejectsManifestWithoutValidSignature() = runTest {
        publish(manifestOf("0.2.0"), signature = null)
        val unsigned = check(currentVersion = "0.1.0")
        publish(manifestOf("0.2.0"), signature = signer.sign(manifestOf("0.1.5")))
        val mismatched = check(currentVersion = "0.1.0")

        assertIs<UpdateCheck.Failed>(unsigned)
        assertIs<UpdateCheck.Failed>(mismatched)
    }

    /** マニフェストを取得できない場合と、署名は合うが中身を読めない場合は、失敗として扱う。 */
    @Test
    fun failsWhenManifestIsMissingOrUnreadable() = runTest {
        val missing = check(currentVersion = "0.1.0")
        publish("{}".encodeToByteArray())
        val unreadable = check(currentVersion = "0.1.0")

        assertIs<UpdateCheck.Failed>(missing)
        assertIs<UpdateCheck.Failed>(unreadable)
    }
}
