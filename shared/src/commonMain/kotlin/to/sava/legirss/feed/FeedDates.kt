package to.sava.legirss.feed

/** フィードに書かれた日時の文字列を、UTC のエポックミリ秒に直す。 */
object FeedDates {
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
    private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
    private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
    private const val TWO_DIGIT_YEAR_LIMIT = 100
    private const val TWO_DIGIT_YEAR_PIVOT = 70

    // 2026-08-15T22:15:00.123+09:00 / 2026-08-15 22:15Z / 2026-08-15
    private val iso8601 = Regex(
        """(\d{4})-(\d{1,2})-(\d{1,2})(?:[Tt ](\d{1,2}):(\d{2})(?::(\d{2})(?:[.,]\d+)?)?)?\s*([Zz]|[+-]\d{2}(?::?\d{2})?)?""",
    )

    // Fri, 14 Aug 2026 18:30:29 +0900 / 14 Aug 26 18:30 GMT
    private val rfc822 = Regex(
        """(?:[A-Za-z]{3,9},?\s+)?(\d{1,2})\s+([A-Za-z]{3})[A-Za-z]*\.?\s+(\d{2,4})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?\s*([+-]\d{2}:?\d{2}|[A-Za-z]{1,5})?""",
    )

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // RFC 822 が定める名前と、日本のフィードで使われる JST。
    private val zoneOffsetHours = mapOf(
        "ut" to 0, "utc" to 0, "gmt" to 0, "z" to 0,
        "jst" to 9,
        "est" to -5, "edt" to -4, "cst" to -6, "cdt" to -5, "mst" to -7, "mdt" to -6, "pst" to -8, "pdt" to -7,
    )

    /** 解釈できなければ null。タイムゾーンの指定が無い日時は UTC とみなす。 */
    fun parse(text: String): Long? = text.trim().let { parseIso8601(it) ?: parseRfc822(it) }

    private fun parseIso8601(text: String): Long? = iso8601.matchEntire(text)?.destructured?.let { (year, month, day, hour, minute, second, zone) ->
        toEpochMillis(year.toInt(), month.toInt(), day.toInt(), hour, minute, second, offsetMillisOf(zone))
    }

    private fun parseRfc822(text: String): Long? = rfc822.matchEntire(text)?.destructured?.let { (day, monthName, year, hour, minute, second, zone) ->
        months.indexOf(monthName.lowercase()).takeIf { it >= 0 }?.let { monthIndex ->
            toEpochMillis(expandYear(year.toInt()), monthIndex + 1, day.toInt(), hour, minute, second, offsetMillisOf(zone))
        }
    }

    private fun expandYear(year: Int): Int = when {
        year >= TWO_DIGIT_YEAR_LIMIT -> year
        year >= TWO_DIGIT_YEAR_PIVOT -> year + 1900
        else -> year + 2000
    }

    private fun offsetMillisOf(zone: String): Long? = when {
        zone.isEmpty() -> 0L
        zone[0] == '+' || zone[0] == '-' -> {
            val digits = zone.drop(1).filter(Char::isDigit).padEnd(4, '0')
            val magnitude = digits.take(2).toInt() * MILLIS_PER_HOUR + digits.drop(2).toInt() * MILLIS_PER_MINUTE
            if (zone[0] == '-') -magnitude else magnitude
        }
        else -> zoneOffsetHours[zone.lowercase()]?.times(MILLIS_PER_HOUR)
    }

    private fun toEpochMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: String,
        minute: String,
        second: String,
        offsetMillis: Long?,
    ): Long? {
        if (offsetMillis == null || month !in 1..12 || day !in 1..31) return null
        val timeOfDay = hour.toLongOrZero() * MILLIS_PER_HOUR +
            minute.toLongOrZero() * MILLIS_PER_MINUTE +
            second.toLongOrZero() * MILLIS_PER_SECOND
        return daysFromCivil(year, month, day) * MILLIS_PER_DAY + timeOfDay - offsetMillis
    }

    private fun String.toLongOrZero(): Long = toLongOrNull() ?: 0L

    /** 1970-01-01 から数えた日数。先発グレゴリオ暦で計算する。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val shiftedYear = if (month <= 2) year - 1L else year.toLong()
        val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
        val yearOfEra = shiftedYear - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }
}
