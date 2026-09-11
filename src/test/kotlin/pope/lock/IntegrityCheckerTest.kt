package pope.lock

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntegrityCheckerTest {
    @Test
    fun `does nothing when there is no existing lock entry`() {
        IntegrityChecker.verify("ba.calculator", "1.0.0", "sha256:new", existingLock = emptyMap())
    }

    @Test
    fun `does nothing when the locked version differs (an expected content change)`() {
        val existingLock = mapOf("ba.calculator" to LockedPackage(version = "1.0.0", integrity = "sha256:old"))

        IntegrityChecker.verify("ba.calculator", "1.0.1", "sha256:new", existingLock)
    }

    @Test
    fun `does nothing when the same version matches its locked hash`() {
        val existingLock = mapOf("ba.calculator" to LockedPackage(version = "1.0.0", integrity = "sha256:same"))

        IntegrityChecker.verify("ba.calculator", "1.0.0", "sha256:same", existingLock)
    }

    @Test
    fun `throws when the same version resolves to a different hash`() {
        val existingLock = mapOf("ba.calculator" to LockedPackage(version = "1.0.0", integrity = "sha256:old"))

        val exception =
            assertFailsWith<IllegalArgumentException> {
                IntegrityChecker.verify("ba.calculator", "1.0.0", "sha256:new", existingLock)
            }

        assertTrue(exception.message!!.contains("ba.calculator"))
        assertTrue(exception.message!!.contains("sha256:old"))
        assertTrue(exception.message!!.contains("sha256:new"))
    }
}
