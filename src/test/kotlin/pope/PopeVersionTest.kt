package pope

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopeVersionTest {
    @Test
    fun `same major is not a mismatch`() {
        assertFalse(PopeVersion.majorMismatch("1.2.0", "1.9.0"))
    }

    @Test
    fun `different major is a mismatch`() {
        assertTrue(PopeVersion.majorMismatch("2.0.0", "1.9.0"))
    }

    @Test
    fun `a SNAPSHOT suffix on either side doesn't break major extraction`() {
        assertFalse(PopeVersion.majorMismatch("1.2.0-SNAPSHOT", "1.9.0"))
        assertTrue(PopeVersion.majorMismatch("2.0.0-SNAPSHOT", "1.9.0"))
    }

    @Test
    fun `null on either side means unknown, not a mismatch`() {
        assertFalse(PopeVersion.majorMismatch(null, "1.9.0"))
        assertFalse(PopeVersion.majorMismatch("1.9.0", null))
        assertFalse(PopeVersion.majorMismatch(null, null))
    }

    @Test
    fun `an unparseable major means unknown, not a mismatch`() {
        assertFalse(PopeVersion.majorMismatch("unknown (not applied from a published version)", "1.9.0"))
    }
}
