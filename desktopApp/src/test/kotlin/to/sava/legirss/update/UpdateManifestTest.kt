package to.sava.legirss.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManifestTest {
    private val signer = ManifestSigner()

    /** 知らせから、版と照合値を読む。それ以外の項目は無視する。照合値は小文字にそろえる。 */
    @Test
    fun readsVersionAndDigest() {
        val release = UpdateManifest.parse(manifestOf("0.2.0", SAMPLE_SHA256.uppercase()))

        assertEquals("0.2.0", release?.version.toString())
        assertEquals(SAMPLE_SHA256, release?.sha256)
    }

    /** 形が合わない知らせ、版や照合値として読めない値を持つ知らせは、読めないものとして扱う。 */
    @Test
    fun rejectsMalformedManifest() {
        assertNull(UpdateManifest.parse("not json".encodeToByteArray()))
        assertNull(UpdateManifest.parse("[]".encodeToByteArray()))
        assertNull(UpdateManifest.parse("""{"android":{"versionName":"0.2.0","sha256":"$SAMPLE_SHA256"}}""".encodeToByteArray()))
        assertNull(UpdateManifest.parse(manifestOf("latest")))
        assertNull(UpdateManifest.parse(manifestOf("0.2.0", sha256 = "abc123")))
        assertNull(UpdateManifest.parse("""{"desktop":{"versionName":2,"sha256":"$SAMPLE_SHA256"}}""".encodeToByteArray()))
    }

    /** 知らせのバイト列に付けた署名は、対になる公開鍵で確かめられる。 */
    @Test
    fun acceptsSignatureMadeWithMatchingKey() {
        val manifest = manifestOf("0.2.0")

        assertTrue(UpdateManifest.isSignedBy(manifest, signer.sign(manifest), signer.publicKey))
    }

    /** 中身を書き換えた知らせ、別の鍵での署名、署名や鍵として読めない文字は、どれも受け付けない。 */
    @Test
    fun rejectsSignatureThatDoesNotMatch() {
        val manifest = manifestOf("0.2.0")
        val signature = signer.sign(manifest)

        assertFalse(UpdateManifest.isSignedBy(manifestOf("9.9.9"), signature, signer.publicKey))
        assertFalse(UpdateManifest.isSignedBy(manifest, ManifestSigner().sign(manifest), signer.publicKey))
        assertFalse(UpdateManifest.isSignedBy(manifest, "not a signature", signer.publicKey))
        assertFalse(UpdateManifest.isSignedBy(manifest, signature, "not a key"))
        assertFalse(UpdateManifest.isSignedBy(manifest, "", signer.publicKey))
    }
}
