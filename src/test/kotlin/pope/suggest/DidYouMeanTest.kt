package pope.suggest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DidYouMeanTest {
    @Test
    fun `suggests the closest candidate for a one-character typo`() {
        assertEquals("calculator", DidYouMean.suggest("claculator", listOf("calculator", "logger", "greeter")))
    }

    @Test
    fun `suggests nothing when candidates are empty`() {
        assertNull(DidYouMean.suggest("calculator", emptyList()))
    }

    @Test
    fun `suggests nothing when nothing is close enough`() {
        assertNull(DidYouMean.suggest("calculator", listOf("greeter", "logger")))
    }

    @Test
    fun `picks the closer of two candidates`() {
        assertEquals("calculator", DidYouMean.suggest("calculater", listOf("calculator", "calibrator")))
    }
}
