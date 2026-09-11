package pope.version

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaretRangeTest {
    @Test
    fun `caret range matches versions within the same major`() {
        assertTrue(CaretRange.satisfies("^1.0.0", SemVer.parse("1.0.0")))
        assertTrue(CaretRange.satisfies("^1.0.0", SemVer.parse("1.4.2")))
        assertFalse(CaretRange.satisfies("^1.0.0", SemVer.parse("2.0.0")))
        assertFalse(CaretRange.satisfies("^1.0.0", SemVer.parse("0.9.9")))
    }

    @Test
    fun `caret range below 1_0_0 is stricter, per npm semantics`() {
        assertTrue(CaretRange.satisfies("^0.2.3", SemVer.parse("0.2.9")))
        assertFalse(CaretRange.satisfies("^0.2.3", SemVer.parse("0.3.0")))
        assertTrue(CaretRange.satisfies("^0.0.3", SemVer.parse("0.0.3")))
        assertFalse(CaretRange.satisfies("^0.0.3", SemVer.parse("0.0.4")))
    }

    @Test
    fun `rejects non-caret ranges for v1`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            CaretRange.satisfies("1.0.0", SemVer.parse("1.0.0"))
        }
    }
}
