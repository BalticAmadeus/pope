package pope.lock

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockfileReaderTest {
    @Test
    fun `returns an empty map when the lockfile doesn't exist`() {
        val missing = File(createTempDirectory("pope-lockfile-test").toFile(), "pope.lock")

        assertTrue(LockfileReader.read(missing).isEmpty())
    }

    @Test
    fun `reads version and integrity for every resolved entry`() {
        val file = createTempDirectory("pope-lockfile-test").toFile().resolve("pope.lock").toPath()
        file.writeText(
            """
            {"resolved": {
              "ba.calculator": { "version": "1.0.1", "source": "...", "integrity": "sha256:abc" },
              "ba.greeter": { "version": "1.0.1", "source": "...", "integrity": "sha256:def" }
            }}
            """.trimIndent(),
        )

        val locked = LockfileReader.read(file.toFile())

        assertEquals(LockedPackage("1.0.1", "sha256:abc"), locked["ba.calculator"])
        assertEquals(LockedPackage("1.0.1", "sha256:def"), locked["ba.greeter"])
    }
}
