package pope.propath

import pope.manifest.Manifest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class PropathGeneratorTest {
    private fun manifest(sourceRoots: List<String>, testRoots: List<String> = emptyList()) =
        Manifest(
            name = "example",
            version = "1.0.0",
            packageName = "example",
            sourceRoots = sourceRoots,
            testRoots = testRoots,
        )

    @Test
    fun `by default, only resolves source roots to absolute paths`() {
        val projectDir = createTempDirectory("propath-generator-test").toFile()

        val propath = PropathGenerator.generate(projectDir, manifest(sourceRoots = listOf("src"), testRoots = listOf("test")))

        assertEquals(listOf(File(projectDir, "src").absolutePath), propath)
    }

    @Test
    fun `includeTests appends test roots after source roots`() {
        val projectDir = createTempDirectory("propath-generator-test").toFile()

        val propath =
            PropathGenerator.generate(
                projectDir,
                manifest(sourceRoots = listOf("src"), testRoots = listOf("test")),
                includeTests = true,
            )

        assertEquals(
            listOf(File(projectDir, "src").absolutePath, File(projectDir, "test").absolutePath),
            propath,
        )
    }

    @Test
    fun `includeTests with no test roots declared behaves the same as the default`() {
        val projectDir = createTempDirectory("propath-generator-test").toFile()

        val propath = PropathGenerator.generate(projectDir, manifest(sourceRoots = listOf("src")), includeTests = true)

        assertEquals(listOf(File(projectDir, "src").absolutePath), propath)
    }
}
