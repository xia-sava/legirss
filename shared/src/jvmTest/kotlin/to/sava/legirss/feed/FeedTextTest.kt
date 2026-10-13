package to.sava.legirss.feed

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedTextTest {
    private val body = "<title>日本語</title>"

    private fun encode(text: String, charsetName: String): ByteArray = text.toByteArray(Charset.forName(charsetName))

    /** XML 宣言に書かれた文字コードで読む。 */
    @Test
    fun usesCharsetFromXmlDeclaration() {
        listOf("Shift_JIS", "EUC-JP", "utf-8").forEach { declared ->
            val text = "<?xml version=\"1.0\" encoding=\"$declared\"?>$body"

            assertEquals(text, FeedText.decode(encode(text, declared), httpCharset = null), declared)
        }
    }

    /** Shift_JIS を名乗る文書に含まれる機種依存文字（丸数字）を読める。 */
    @Test
    fun readsVendorCharactersInShiftJis() {
        val text = "<?xml version=\"1.0\" encoding=\"Shift_JIS\"?><title>①㈱</title>"

        assertEquals(text, FeedText.decode(encode(text, "windows-31j"), httpCharset = null))
    }

    /** XML 宣言に文字コードが無ければ、HTTP ヘッダの文字コードで読む。 */
    @Test
    fun fallsBackToHttpCharset() {
        val text = "<?xml version=\"1.0\"?>$body"

        assertEquals(text, FeedText.decode(encode(text, "EUC-JP"), httpCharset = "EUC-JP"))
    }

    /** XML 宣言の文字コードは HTTP ヘッダの文字コードより優先する。 */
    @Test
    fun prefersXmlDeclarationOverHttpCharset() {
        val text = "<?xml version='1.0' encoding='EUC-JP'?>$body"

        assertEquals(text, FeedText.decode(encode(text, "EUC-JP"), httpCharset = "ISO-8859-1"))
    }

    /** BOM があればそれに従い、BOM 自体は取り除く。 */
    @Test
    fun honorsByteOrderMark() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

        assertEquals(body, FeedText.decode(utf8Bom + encode(body, "UTF-8"), httpCharset = "EUC-JP"))
        assertEquals(body, FeedText.decode(utf16LeBom + encode(body, "UTF-16LE"), httpCharset = null))
    }

    /** どこにも文字コードが無いときと、知らない名前のときは UTF-8 で読む。 */
    @Test
    fun defaultsToUtf8() {
        val unknown = "<?xml version=\"1.0\" encoding=\"x-no-such-charset\"?>$body"

        assertEquals(body, FeedText.decode(encode(body, "UTF-8"), httpCharset = null))
        assertEquals(unknown, FeedText.decode(encode(unknown, "UTF-8"), httpCharset = null))
    }
}
