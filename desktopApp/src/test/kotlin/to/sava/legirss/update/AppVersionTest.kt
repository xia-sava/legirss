package to.sava.legirss.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    /** 版は先頭の数から順に、文字としてではなく数として比べる。 */
    @Test
    fun comparesPartsNumerically() {
        assertTrue(version("0.10.0") > version("0.9.9"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertTrue(version("0.1.1") > version("0.1.0"))
        assertEquals(0, version("0.1.0").compareTo(version("0.1.0")))
    }

    /** 長さの違う版は、足りない側を 0 として比べる。 */
    @Test
    fun treatsMissingPartsAsZero() {
        assertEquals(0, version("1.2").compareTo(version("1.2.0")))
        assertTrue(version("1.2.1") > version("1.2"))
    }

    /** 数と点だけの形でない文字は、版として読まない。 */
    @Test
    fun rejectsTextThatIsNotVersion() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v1.0.0"))
        assertNull(AppVersion.parse("1.0.0-beta"))
        assertNull(AppVersion.parse("1..0"))
        assertNull(AppVersion.parse("1.-2.0"))
    }

    /** 読んだ版を文字に戻すと、元の書き方になる。前後の空白は無視する。 */
    @Test
    fun printsAsWritten() {
        assertEquals("0.12.3", version(" 0.12.3\n").toString())
    }
}
