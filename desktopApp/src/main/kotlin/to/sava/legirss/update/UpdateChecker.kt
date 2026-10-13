package to.sava.legirss.update

/** 配布物を置く場所。配布中の版は、いつも同じ名前のリリースに置かれる。 */
internal object UpdateSource {
    private const val REPOSITORY = "xia-sava/legirss"
    private const val RELEASE_TAG = "latest"

    const val MANIFEST_URL = "https://github.com/$REPOSITORY/releases/download/$RELEASE_TAG/latest.json"
    const val SIGNATURE_URL = "$MANIFEST_URL.sig"
    const val INSTALLER_URL = "https://github.com/$REPOSITORY/releases/download/$RELEASE_TAG/legirss.msi"
}

/** 新しい版があるかを確かめた結果。 */
internal sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    data class Available(val release: UpdateRelease) : UpdateCheck

    data class Failed(val reason: String) : UpdateCheck
}

/**
 * 配布中の版の知らせを取得して、動いている版より新しいかを確かめる。
 *
 * 知らせは、署名を確かめてからでないと読まない。署名が取れない、合わない場合はどれも失敗にする。
 *
 * @param fetch URL の中身を取得する。取得できなければ null を返す。
 */
internal class UpdateChecker(
    private val currentVersion: AppVersion,
    private val publicKey: String,
    private val fetch: suspend (url: String) -> ByteArray?,
) {
    suspend fun check(): UpdateCheck {
        val manifest = fetch(UpdateSource.MANIFEST_URL) ?: return UpdateCheck.Failed(FETCH_FAILED)
        val signature = fetch(UpdateSource.SIGNATURE_URL) ?: return UpdateCheck.Failed(SIGNATURE_UNVERIFIED)
        if (!UpdateManifest.isSignedBy(manifest, signature.decodeToString(), publicKey)) {
            return UpdateCheck.Failed(SIGNATURE_UNVERIFIED)
        }
        val release = UpdateManifest.parse(manifest) ?: return UpdateCheck.Failed(UNREADABLE)
        return if (release.version > currentVersion) UpdateCheck.Available(release) else UpdateCheck.UpToDate
    }

    private companion object {
        const val FETCH_FAILED = "配布中の版の知らせを取得できません"
        const val SIGNATURE_UNVERIFIED = "配布中の版の知らせの署名を確かめられません"
        const val UNREADABLE = "配布中の版の知らせを読めません"
    }
}
