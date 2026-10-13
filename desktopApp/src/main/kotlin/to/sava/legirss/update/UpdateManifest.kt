package to.sava.legirss.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** 配布元が知らせる、配布中の版。 */
internal data class UpdateRelease(
    val version: AppVersion,
    /** 配布物の SHA-256（16 進の小文字）。取得した配布物の照合に使う。 */
    val sha256: String,
)

/**
 * 配布元が置く、配布中の版を書いたマニフェスト（`latest.json`）。
 *
 * 配布物を取りにいく場所はアプリが決めていて、ここからは受け取らない。マニフェストが動かせるのは版と照合値だけになる。
 */
internal object UpdateManifest {
    /**
     * マニフェストに付く署名を確かめる鍵。X.509 SubjectPublicKeyInfo の DER を base64 にした、ECDSA P-256 の公開鍵。
     *
     * 配布の経路ともマニフェストの中身とも別に持つ、信頼の起点になる。
     */
    const val PUBLIC_KEY: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEGqes4mE7TNzloY14nqHF6zB7bXa2COwF1qT9/4vktP0+rDMQsZEtWy2/KnoGoxiQh1UIB83jXkgekgD2XfCsmg=="

    private const val PLATFORM_KEY = "desktop"
    private const val VERSION_KEY = "versionName"
    private const val SHA256_KEY = "sha256"
    private const val KEY_ALGORITHM = "EC"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    /** [manifest] を読む。形が合わないか、版や照合値として読めない値があれば null。 */
    fun parse(manifest: ByteArray): UpdateRelease? {
        val platform = try {
            (Json.parseToJsonElement(manifest.decodeToString()) as? JsonObject)?.get(PLATFORM_KEY) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        fun textOf(key: String): String? = (platform[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = textOf(VERSION_KEY)?.let(AppVersion::parse) ?: return null
        val sha256 = textOf(SHA256_KEY)?.lowercase()?.takeIf(sha256Pattern::matches) ?: return null
        return UpdateRelease(version, sha256)
    }

    /**
     * [manifest] のバイト列に対する [signature]（DER の署名を base64 にしたもの）を、[publicKey] で確かめる。
     *
     * 署名や鍵が読めない場合と、署名が合わない場合は、どれも false を返す。
     */
    fun isSignedBy(manifest: ByteArray, signature: String, publicKey: String): Boolean =
        try {
            val key = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(manifest)
                verify(Base64.getDecoder().decode(signature.trim()))
            }
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
}
