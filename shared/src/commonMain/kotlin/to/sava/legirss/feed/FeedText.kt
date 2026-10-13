package to.sava.legirss.feed

/**
 * [bytes] を [charsetName] の文字コードとして文字列にする。その文字コードを扱えなければ null。
 */
internal expect fun decodeText(bytes: ByteArray, charsetName: String): String?

/** 取得したフィードのバイト列を文字列にする。 */
object FeedText {
    private const val DEFAULT_CHARSET = "UTF-8"
    private const val DECLARATION_SCAN_LENGTH = 200
    private val encodingAttribute = Regex("""<\?xml[^>]*\sencoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")

    private val byteOrderMarks = listOf(
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to "UTF-8",
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to "UTF-16LE",
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to "UTF-16BE",
    )

    /**
     * 文字コードは BOM、XML 宣言、HTTP ヘッダの順に見て決める。どれにも無いか、扱えない名前なら UTF-8 とする。
     *
     * @param httpCharset HTTP の Content-Type ヘッダに書かれていた文字コード。
     */
    fun decode(bytes: ByteArray, httpCharset: String?): String {
        byteOrderMarks.firstOrNull { (mark, _) -> bytes.startsWith(mark) }?.let { (mark, charset) ->
            return decodeOrDefault(bytes.copyOfRange(mark.size, bytes.size), charset)
        }
        return decodeOrDefault(bytes, declaredCharsetOf(bytes) ?: httpCharset ?: DEFAULT_CHARSET)
    }

    private fun decodeOrDefault(bytes: ByteArray, charsetName: String): String =
        decodeText(bytes, charsetName) ?: decodeText(bytes, DEFAULT_CHARSET).orEmpty()

    /** XML 宣言の encoding 属性。宣言は ASCII の範囲で書かれるので、先頭を 1 バイト 1 文字として読む。 */
    private fun declaredCharsetOf(bytes: ByteArray): String? =
        bytes.take(DECLARATION_SCAN_LENGTH)
            .map { (it.toInt() and 0xFF).toChar() }
            .joinToString("")
            .let(encodingAttribute::find)
            ?.groupValues
            ?.get(1)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
