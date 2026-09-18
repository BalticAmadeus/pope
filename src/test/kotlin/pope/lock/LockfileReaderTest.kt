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

        // installSubpath/files aren't in this fixture (an older lockfile predating them) - both
        // default rather than failing to parse.
        assertEquals(LockedPackage("1.0.1", "sha256:abc"), locked["ba.calculator"])
        assertEquals(LockedPackage("1.0.1", "sha256:def"), locked["ba.greeter"])
    }

    @Test
    fun `reads installSubpath and files when present`() {
        val file = createTempDirectory("pope-lockfile-test").toFile().resolve("pope.lock").toPath()
        file.writeText(
            """
            {"resolved": {
              "ba.calculator": {
                "version": "1.0.1",
                "source": "...",
                "integrity": "sha256:abc",
                "installSubpath": "registry-ba",
                "files": ["ba/calculator/Calculator.cls"]
              }
            }}
            """.trimIndent(),
        )

        val locked = LockfileReader.read(file.toFile())

        assertEquals(
            LockedPackage("1.0.1", "sha256:abc", installSubpath = "registry-ba", files = listOf("ba/calculator/Calculator.cls")),
            locked["ba.calculator"],
        )
    }
}
