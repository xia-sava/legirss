package to.sava.legirss.update

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** テスト用に、知らせへ署名する鍵の組を作って使う。 */
internal class ManifestSigner {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** アプリに埋め込むのと同じ形の公開鍵。 */
    val publicKey: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** 配布元が付けるのと同じ形の署名。 */
    fun sign(manifest: ByteArray): String =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(manifest)
            Base64.getEncoder().encodeToString(sign())
        }
}

internal const val SAMPLE_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

internal fun manifestOf(versionName: String, sha256: String = SAMPLE_SHA256): ByteArray =
    """{"desktop":{"versionName":"$versionName","url":"https://example.com/ignored.msi","sha256":"$sha256"}}""".encodeToByteArray()

internal fun version(text: String): AppVersion = requireNotNull(AppVersion.parse(text))
