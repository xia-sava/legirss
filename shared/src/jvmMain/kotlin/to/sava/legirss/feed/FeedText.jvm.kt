package to.sava.legirss.feed

import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

// Shift_JIS を名乗るフィードは機種依存文字（丸数字など）を含むことが多いので、それらを含む上位互換の文字コードで読む。
private val charsetAliases = mapOf(
    "shift_jis" to "windows-31j",
    "shift-jis" to "windows-31j",
    "sjis" to "windows-31j",
    "x-sjis" to "windows-31j",
)

internal actual fun decodeText(bytes: ByteArray, charsetName: String): String? =
    try {
        String(bytes, Charset.forName(charsetAliases[charsetName.lowercase()] ?: charsetName))
    } catch (_: IllegalCharsetNameException) {
        null
    } catch (_: UnsupportedCharsetException) {
        null
    }
