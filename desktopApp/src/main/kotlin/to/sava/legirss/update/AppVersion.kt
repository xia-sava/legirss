package to.sava.legirss.update

import java.io.IOException
import java.util.Properties

/** アプリの版。`1.2.3` のように、点で区切った数の並びで表す。 */
internal class AppVersion private constructor(private val parts: List<Int>) : Comparable<AppVersion> {
    /** 先頭の数から順に比べる。長さが違うときは、足りない側を 0 として扱う。 */
    override fun compareTo(other: AppVersion): Int =
        (0 until maxOf(parts.size, other.parts.size))
            .map { index -> (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0) }
            .firstOrNull { it != 0 }
            ?: 0

    override fun toString(): String = parts.joinToString(".")

    companion object {
        private const val VERSION_RESOURCE = "/legirss-version.properties"
        private const val VERSION_KEY = "version"

        /** [text] を版として読む。数と点だけの形でなければ null。 */
        fun parse(text: String): AppVersion? =
            text.trim().split('.')
                .map { it.toIntOrNull()?.takeIf { number -> number >= 0 } ?: return null }
                .let(::AppVersion)

        /** 動いているアプリ自身の版。ビルドのときに埋め込んだ値を読む。読めなければ null。 */
        fun current(): AppVersion? =
            AppVersion::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { stream ->
                try {
                    Properties().apply { load(stream) }.getProperty(VERSION_KEY)?.let(::parse)
                } catch (_: IOException) {
                    null
                }
            }
    }
}
