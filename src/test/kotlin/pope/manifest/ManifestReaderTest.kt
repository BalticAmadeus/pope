package pope.manifest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManifestReaderTest {
    @Test
    fun `throws when manifest file is missing`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestReader.read(File("does-not-exist.json"))
        }
    }

    @Test
    fun `throws when package_name is missing and there's no buildPath source entry to infer it from`() {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {"name": "consumer-app", "version": "1.0.0"}
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> {
            ManifestReader.read(file.toFile())
        }
    }

    @Test
    fun `infers and persists package_name from cls files when it's missing`() {
        val dir = createTempDirectory("manifest-reader-test").toFile()
        val file = File(dir, "openedge-project.json")
        file.writeText(
            """
            {
              "name": "closer-package",
              "version": "1.0.0",
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(dir, "src/example/closer/Closer.cls").apply { parentFile.mkdirs() }.writeText(
            "class example.closer.Closer:\nend class.\n",
        )

        val manifest = ManifestReader.read(file)

        assertEquals("example.closer", manifest.packageName)
        assertTrue(
            file.readText().contains("\"package_name\": \"example.closer\""),
            "Expected the inferred package_name to be written back to the manifest file on disk",
        )
    }

    @Test
    fun `throws when package_name is missing and no cls files exist under the source root to infer it from`() {
        val dir = createTempDirectory("manifest-reader-test").toFile()
        val file = File(dir, "openedge-project.json")
        file.writeText(
            """
            {
              "name": "empty-package",
              "version": "1.0.0",
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(dir, "src").mkdirs()

        assertFailsWith<IllegalStateException> {
            ManifestReader.read(file)
        }
    }

    @Test
    fun `reads name, version, package_name, and dependencies from openedge-project json`() {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "oeversion": "12.8",
              "package_name": "example.consumer",
              "dependencies": { "example.calculator": "^1.0.0" },
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )

        val manifest = ManifestReader.read(file.toFile())

        assertEquals("consumer-app", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals("example.consumer", manifest.packageName)
        assertEquals(mapOf("example.calculator" to DependencySpec.Registry("^1.0.0")), manifest.dependencies)
        assertEquals(listOf("src"), manifest.sourceRoots)
    }

    @Test
    fun `sourceRoots only includes buildPath entries of type source, in order`() {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "package_name": "example.consumer",
              "buildPath": [
                { "type": "source", "path": "src" },
                { "type": "other", "path": "ignored" },
                { "type": "source", "path": "pope_packages/example.calculator/src" }
              ]
            }
            """.trimIndent(),
        )

        val manifest = ManifestReader.read(file.toFile())

        assertEquals(listOf("src", "pope_packages/example.calculator/src"), manifest.sourceRoots)
    }

    @Test
    fun `testRoots only includes buildPath entries of type test, in order, separately from sourceRoots`() {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "package_name": "example.consumer",
              "buildPath": [
                { "type": "source", "path": "src" },
                { "type": "test", "path": "test" },
                { "type": "other", "path": "ignored" },
                { "type": "test", "path": "test/fixtures" }
              ]
            }
            """.trimIndent(),
        )

        val manifest = ManifestReader.read(file.toFile())

        assertEquals(listOf("src"), manifest.sourceRoots)
        assertEquals(listOf("test", "test/fixtures"), manifest.testRoots)
    }

    @Test
    fun `parses a direct-source dependency`() {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "calculator-package",
              "version": "1.0.0",
              "package_name": "calculator",
              "dependencies": { "greeter": { "repoUrl": "https://example.com/greeter.git", "ref": "v1.0.1" } },
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )

        val manifest = ManifestReader.read(file.toFile())

        assertEquals(
            mapOf("greeter" to DependencySpec.DirectSource("https://example.com/greeter.git", "v1.0.1")),
            manifest.dependencies,
        )
    }

    @Test
    fun `throws when a direct-source dependency is missing repoUrl or ref`() {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "calculator-package",
              "version": "1.0.0",
              "package_name": "calculator",
              "dependencies": { "greeter": { "ref": "v1.0.1" } },
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> {
            ManifestReader.read(file.toFile())
        }
    }

    @Test
    fun `throws when a dependency entry is neither a version string nor a repoUrl-ref object`() {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "calculator-package",
              "version": "1.0.0",
              "package_name": "calculator",
              "dependencies": { "greeter": 42 },
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> {
            ManifestReader.read(file.toFile())
        }
    }
}
