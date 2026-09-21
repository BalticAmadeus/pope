package pope.manifest

import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependenciesUpdaterTest {
    private fun manifestWithDependencies(dependenciesJson: String): File {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "pope_package_name": "example.consumer",
              "pope_dependencies": $dependenciesJson,
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        return file.toFile()
    }

    @Test
    fun `adds a new dependency to an empty map`() {
        val file = manifestWithDependencies("{}")

        DependenciesUpdater.addDependency(file, "example.calculator", "^1.0.0")

        val dependencies = JSONObject(file.readText()).getJSONObject("pope_dependencies")
        assertEquals("^1.0.0", dependencies.getString("example.calculator"))
    }

    @Test
    fun `adds a dependency alongside existing ones without touching them`() {
        val file = manifestWithDependencies("""{"example.greeter": "^2.0.0"}""")

        DependenciesUpdater.addDependency(file, "example.calculator", "^1.0.0")

        val dependencies = JSONObject(file.readText()).getJSONObject("pope_dependencies")
        assertEquals("^1.0.0", dependencies.getString("example.calculator"))
        assertEquals("^2.0.0", dependencies.getString("example.greeter"))
    }

    @Test
    fun `overwrites the version spec when the dependency already exists`() {
        val file = manifestWithDependencies("""{"example.calculator": "^1.0.0"}""")

        DependenciesUpdater.addDependency(file, "example.calculator", "^2.0.0")

        val dependencies = JSONObject(file.readText()).getJSONObject("pope_dependencies")
        assertEquals("^2.0.0", dependencies.getString("example.calculator"))
    }

    @Test
    fun `removes a dependency, leaving others untouched`() {
        val file = manifestWithDependencies("""{"example.calculator": "^1.0.0", "example.greeter": "^2.0.0"}""")

        DependenciesUpdater.removeDependency(file, "example.calculator")

        val dependencies = JSONObject(file.readText()).getJSONObject("pope_dependencies")
        assertTrue(!dependencies.has("example.calculator"))
        assertEquals("^2.0.0", dependencies.getString("example.greeter"))
    }

    @Test
    fun `removing a dependency that isn't declared is a no-op`() {
        val file = manifestWithDependencies("""{"example.greeter": "^2.0.0"}""")

        DependenciesUpdater.removeDependency(file, "example.calculator")

        val dependencies = JSONObject(file.readText()).getJSONObject("pope_dependencies")
        assertEquals("^2.0.0", dependencies.getString("example.greeter"))
    }

    @Test
    fun `adding a dependency that didn't exist yet places the key right after version`() {
        val file = createTempFile(suffix = ".json").toFile()
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "pope_package_name": "example.consumer",
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )

        DependenciesUpdater.addDependency(file, "example.calculator", "^1.0.0")

        // org.json's JSONObject is backed by a plain HashMap, so re-parsing
        // and reading keySet() back would lose order again - only the raw
        // text can confirm where the newly-added key actually landed.
        val text = file.readText()
        assertTrue(
            text.indexOf("\"version\"") < text.indexOf("\"pope_dependencies\"") &&
                text.indexOf("\"pope_dependencies\"") < text.indexOf("\"buildPath\""),
            "Expected \"pope_dependencies\" right after \"version\", got:\n$text",
        )
    }
}
