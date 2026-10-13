package to.sava.legirss.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedDatesTest {
    // 2026-08-14T09:30:29Z
    private val reference = 1_786_699_829_000L

    /** RFC 822 形式を、数値のオフセットとタイムゾーン名の両方で読める。 */
    @Test
    fun parsesRfc822() {
        listOf(
            "Fri, 14 Aug 2026 18:30:29 +0900",
            "Fri, 14 Aug 2026 09:30:29 GMT",
            "14 Aug 2026 18:30:29 JST",
            "Fri, 14 Aug 2026 05:30:29 EDT",
            "Friday, 14 August 2026 09:30:29 +00:00",
            "Fri, 14 Aug 26 09:30:29 Z",
        ).forEach { text -> assertEquals(reference, FeedDates.parse(text), text) }
    }

    /** ISO 8601 形式を、秒の小数やコロンの無いオフセットを含めて読める。 */
    @Test
    fun parsesIso8601() {
        listOf(
            "2026-08-14T18:30:29+09:00",
            "2026-08-14T09:30:29Z",
            "2026-08-14T09:30:29.250Z",
            "2026-08-14 18:30:29+0900",
            "2026-08-14T04:00:29-05:30",
        ).forEach { text -> assertEquals(reference, FeedDates.parse(text), text) }
    }

    /** 時刻や秒が省かれていれば 0 とみなし、タイムゾーンが無ければ UTC とみなす。 */
    @Test
    fun fillsMissingParts() {
        assertEquals(reference - 29_000L, FeedDates.parse("2026-08-14T09:30Z"))
        assertEquals(1_786_665_600_000L, FeedDates.parse("2026-08-14"))
        assertEquals(reference, FeedDates.parse("Fri, 14 Aug 2026 09:30:29"))
    }

    /** うるう年をまたぐ日付と、エポックより前の日付を正しく数える。 */
    @Test
    fun countsDaysAcrossLeapYears() {
        assertEquals(951_782_400_000L, FeedDates.parse("2000-02-29T00:00:00Z"))
        assertEquals(951_868_800_000L, FeedDates.parse("2000-03-01T00:00:00Z"))
        assertEquals(-86_400_000L, FeedDates.parse("1969-12-31T00:00:00Z"))
    }

    /** 日時として読めない文字列と、知らないタイムゾーン名は null になる。 */
    @Test
    fun rejectsUnreadableText() {
        listOf("", "yesterday", "2026-13-01", "Fri, 14 Foo 2026 09:30:29 GMT", "14 Aug 2026 09:30:29 XYZ")
            .forEach { text -> assertNull(FeedDates.parse(text), text) }
    }
}
