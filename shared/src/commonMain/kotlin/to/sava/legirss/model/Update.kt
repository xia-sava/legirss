package to.sava.legirss.model

/** チャンネルの直近の更新が失敗した理由。 */
enum class ChannelError(val code: Long) {
    NONE(0),

    /** 接続できない、応答が時間内に返らない、など。 */
    NO_RESPONSE(1),

    /** 成功以外のステータスが返った。 */
    HTTP_ERROR(2),

    /** 取得できたが、フィードとして読めなかった。 */
    NOT_A_FEED(3),
}

/** 既読の記事をチャンネルごとにどこまで残すか。未読の記事と保護された記事は、これに関わらず残す。 */
data class Retention(
    /** 新しい方から数えて残す数。 */
    val keepCount: Int,
    /** 残す日数。記事の日時がこれより古いものは残さない。 */
    val keepDays: Int,
) {
    companion object {
        val DEFAULT = Retention(keepCount = 100, keepDays = 60)

        /** 残す数と残す日数を保存する設定の名前。 */
        const val KEEP_COUNT_KEY = "keep_count"
        const val KEEP_DAYS_KEY = "keep_days"
    }
}

/** 更新の対象になるチャンネルと、取得に使う前回の情報。 */
data class UpdateTarget(
    val channelId: Long,
    val feedUrl: String,
    val etag: String?,
    val lastModified: String?,
    /** 次に取得に成功したとき、得た記事を既読として登録する。 */
    val firstFetchAsRead: Boolean,
    /** フィードが無くなったことを示す応答を数えた回数。 */
    val goneCount: Int,
    /** その応答を最後に数えた時刻。 */
    val goneAt: Long?,
)
