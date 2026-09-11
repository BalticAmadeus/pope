package pope.registry

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDirectoryRegistryTest {
    private fun registryWithPackage(packageName: String, version: String): File {
        val root = createTempDirectory("pope-registry-test").toFile()
        val packageDir = File(root, "some-folder-name").apply { mkdirs() }
        File(packageDir, "openedge-project.json").writeText(
            """
            {
              "name": "$packageName-project",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(packageDir, "src").mkdirs()
        return root
    }

    @Test
    fun `resolves a package matching name and satisfying the version range`() {
        val root = registryWithPackage("example.calculator", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val resolved = registry.resolve("example.calculator", "^1.0.0")

        assertEquals("1.0.0", resolved.version)
        assertEquals("example.calculator", resolved.packageName)
        assertEquals("src", resolved.sourceDir.name)
    }

    @Test
    fun `throws when no package with that name exists`() {
        val root = registryWithPackage("example.calculator", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        assertFailsWith<IllegalStateException> {
            registry.resolve("example.other", "^1.0.0")
        }
    }

    @Test
    fun `throws when the found version does not satisfy the range`() {
        val root = registryWithPackage("example.calculator", "2.0.0")
        val registry = LocalDirectoryRegistry(root)

        assertFailsWith<IllegalStateException> {
            registry.resolve("example.calculator", "^1.0.0")
        }
    }

    @Test
    fun `findAny returns a package by name regardless of version`() {
        val root = registryWithPackage("example.calculator", "3.2.1")
        val registry = LocalDirectoryRegistry(root)

        val found = registry.findAny("example.calculator")

        assertEquals("3.2.1", found?.version)
    }

    @Test
    fun `findAny returns null when no package with that name exists`() {
        val root = registryWithPackage("example.calculator", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        assertNull(registry.findAny("example.other"))
    }

    @Test
    fun `throws when two different folders declare the same package_name`() {
        val root = createTempDirectory("pope-registry-test").toFile()
        for (folderName in listOf("greeter-package", "greeter-two-package")) {
            val packageDir = File(root, folderName).apply { mkdirs() }
            File(packageDir, "openedge-project.json").writeText(
                """
                {
                  "name": "$folderName",
                  "version": "1.0.0",
                  "package_name": "example.greeter",
                  "dependencies": {},
                  "buildPath": [{ "type": "source", "path": "src" }]
                }
                """.trimIndent(),
            )
            File(packageDir, "src").mkdirs()
        }
        val registry = LocalDirectoryRegistry(root)

        val exception = assertFailsWith<IllegalStateException> { registry.findAny("example.greeter") }
        assertTrue(exception.message!!.contains("greeter-package"))
        assertTrue(exception.message!!.contains("greeter-two-package"))
    }
}
