package pope.manifest

import org.json.JSONObject
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildPathUpdaterTest {
    private fun manifestWithBuildPath(vararg paths: String): java.io.File {
        val file = createTempFile(suffix = ".json")
        val buildPath = paths.joinToString(",") { """{"type": "source", "path": "$it"}""" }
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "package_name": "example.consumer",
              "dependencies": {},
              "buildPath": [$buildPath]
            }
            """.trimIndent(),
        )
        return file.toFile()
    }

    private fun buildPathOf(file: java.io.File): List<String> {
        val array = JSONObject(file.readText()).getJSONArray("buildPath")
        return (0 until array.length()).map { array.getJSONObject(it).getString("path") }
    }

    @Test
    fun `appends a missing source entry after existing ones`() {
        val file = manifestWithBuildPath("src")

        BuildPathUpdater.ensureSourceEntries(file, listOf("pope_packages/example.calculator/src"))

        assertEquals(listOf("src", "pope_packages/example.calculator/src"), buildPathOf(file))
    }

    @Test
    fun `does not duplicate an entry that already exists`() {
        val file = manifestWithBuildPath("src", "pope_packages/example.calculator/src")

        BuildPathUpdater.ensureSourceEntries(file, listOf("pope_packages/example.calculator/src"))

        assertEquals(listOf("src", "pope_packages/example.calculator/src"), buildPathOf(file))
    }

    @Test
    fun `the same new path repeated in one call is only added once`() {
        val file = manifestWithBuildPath("src")

        BuildPathUpdater.ensureSourceEntries(
            file,
            listOf("pope_packages/registry-ba", "pope_packages/registry-ba"),
        )

        assertEquals(listOf("src", "pope_packages/registry-ba"), buildPathOf(file))
    }

    @Test
    fun `adds multiple missing entries while preserving existing ones`() {
        val file = manifestWithBuildPath("src")

        BuildPathUpdater.ensureSourceEntries(
            file,
            listOf("pope_packages/example.calculator/src", "pope_packages/example.greeter/src"),
        )

        assertEquals(
            listOf("src", "pope_packages/example.calculator/src", "pope_packages/example.greeter/src"),
            buildPathOf(file),
        )
    }

    @Test
    fun `pruneStalePopePackagesEntries removes only pope_packages source entries not in expectedPaths`() {
        val file =
            manifestWithBuildPath(
                "src",
                "pope_packages/example.calculator/src",
                "pope_packages/example.greeter/src",
            )

        val removed =
            BuildPathUpdater.pruneStalePopePackagesEntries(file, expectedPaths = setOf("pope_packages/example.calculator/src"))

        assertEquals(listOf("pope_packages/example.greeter/src"), removed)
        assertEquals(listOf("src", "pope_packages/example.calculator/src"), buildPathOf(file))
    }

    @Test
    fun `pruneStalePopePackagesEntries never touches entries outside pope_packages`() {
        val file = manifestWithBuildPath("src", "some/other/entry")

        val removed = BuildPathUpdater.pruneStalePopePackagesEntries(file, expectedPaths = emptySet())

        assertEquals(emptyList(), removed)
        assertEquals(listOf("src", "some/other/entry"), buildPathOf(file))
    }

    @Test
    fun `pruneStalePopePackagesEntries with dryRun reports what would be removed but changes nothing`() {
        val file = manifestWithBuildPath("src", "pope_packages/example.calculator/src")

        val removed = BuildPathUpdater.pruneStalePopePackagesEntries(file, expectedPaths = emptySet(), dryRun = true)

        assertEquals(listOf("pope_packages/example.calculator/src"), removed)
        assertEquals(listOf("src", "pope_packages/example.calculator/src"), buildPathOf(file))
    }

    @Test
    fun `pruneStalePopePackagesEntries returns empty and leaves buildPath untouched when nothing is stale`() {
        val file = manifestWithBuildPath("src", "pope_packages/example.calculator/src")

        val removed =
            BuildPathUpdater.pruneStalePopePackagesEntries(file, expectedPaths = setOf("pope_packages/example.calculator/src"))

        assertEquals(emptyList(), removed)
        assertEquals(listOf("src", "pope_packages/example.calculator/src"), buildPathOf(file))
    }
}
