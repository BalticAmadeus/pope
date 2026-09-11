package pope.registry

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegistriesPropertiesFileTest {
    private fun tempFile(): File = File(createTempDirectory("pope-registries-test").toFile(), "pope-registries.properties")

    @Test
    fun `reading a missing file returns an empty list`() {
        assertEquals(emptyList(), RegistriesPropertiesFile.read(tempFile()))
    }

    @Test
    fun `reads populated entries`() {
        val file = tempFile()
        file.writeText(
            """
            ba.prefix=ba.
            ba.catalogUrl=https://github.com/erudys27/registry-ba.git
            cw.prefix=cw.
            cw.catalogUrl=https://github.com/erudys27/registry-cw.git
            """.trimIndent(),
        )

        val entries = RegistriesPropertiesFile.read(file)

        assertEquals(
            listOf(
                RegistryFileEntry("ba", "ba.", "https://github.com/erudys27/registry-ba.git", null),
                RegistryFileEntry("cw", "cw.", "https://github.com/erudys27/registry-cw.git", null),
            ),
            entries,
        )
    }

    @Test
    fun `add creates the file if missing`() {
        val file = tempFile()

        RegistriesPropertiesFile.add(file, "ba", "ba.", "https://github.com/erudys27/registry-ba.git")

        assertTrue(file.exists())
        assertEquals(
            listOf(RegistryFileEntry("ba", "ba.", "https://github.com/erudys27/registry-ba.git", null)),
            RegistriesPropertiesFile.read(file),
        )
    }

    @Test
    fun `add appends without disturbing existing entries`() {
        val file = tempFile()
        RegistriesPropertiesFile.add(file, "ba", "ba.", "https://github.com/erudys27/registry-ba.git")

        RegistriesPropertiesFile.add(file, "cw", "cw.", "https://github.com/erudys27/registry-cw.git")

        val entries = RegistriesPropertiesFile.read(file)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.name == "ba" })
        assertTrue(entries.any { it.name == "cw" })
    }

    @Test
    fun `add throws on a duplicate name`() {
        val file = tempFile()
        RegistriesPropertiesFile.add(file, "ba", "ba.", "https://github.com/erudys27/registry-ba.git")

        assertFailsWith<IllegalArgumentException> {
            RegistriesPropertiesFile.add(file, "ba", "ba2.", "https://example.com/other.git")
        }
    }

    @Test
    fun `add throws on a duplicate prefix under a different name`() {
        val file = tempFile()
        RegistriesPropertiesFile.add(file, "ba", "ba.", "https://github.com/erudys27/registry-ba.git")

        assertFailsWith<IllegalArgumentException> {
            RegistriesPropertiesFile.add(file, "baltic", "ba.", "https://example.com/other.git")
        }
    }
}
