package pope.manifest

import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertTrue

class ManifestWriterTest {
    // org.json's JSONObject is backed by a plain HashMap, so re-parsing the
    // written file and reading its keySet() back would lose order again -
    // key order can only be verified from the raw text itself.
    private fun assertKeyOrder(text: String, vararg keysInOrder: String) {
        val positions = keysInOrder.map { key -> text.indexOf("\"$key\"") }
        positions.forEach { position -> assertTrue(position >= 0, "Expected to find all of ${keysInOrder.toList()} in:\n$text") }
        assertTrue(
            positions == positions.sorted(),
            "Expected ${keysInOrder.toList()} in that order, got positions $positions in:\n$text",
        )
    }

    @Test
    fun `writes keys in the canonical order (name, version, oeversion, package_name, dependencies, buildPath), regardless of insertion order`() {
        val file = createTempFile(suffix = ".json").toFile()
        val json =
            JSONObject()
                .put("buildPath", JSONArray())
                .put("oeversion", "12.8")
                .put("version", "1.0.0")
                .put("popeDependencies", JSONObject())
                .put("name", "customer-app")
                .put("popePackageName", "example.customer")

        ManifestWriter.write(file, json)

        assertKeyOrder(file.readText(), "name", "version", "oeversion", "popePackageName", "popeDependencies", "buildPath")
    }

    @Test
    fun `keeps unrecognized keys, appended after the canonical ones`() {
        val file = createTempFile(suffix = ".json").toFile()
        val json =
            JSONObject()
                .put("name", "customer-app")
                .put("version", "1.0.0")
                .put("someFutureField", "x")

        ManifestWriter.write(file, json)

        assertKeyOrder(file.readText(), "name", "version", "someFutureField")
    }

    @Test
    fun `written file is valid JSON containing every original value`() {
        val file = createTempFile(suffix = ".json").toFile()
        val json =
            JSONObject()
                .put("name", "customer-app")
                .put("version", "1.0.0")
                .put("popePackageName", "example.customer")
                .put("popeDependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))

        ManifestWriter.write(file, json)

        val reparsed = JSONObject(file.readText())
        kotlin.test.assertEquals("customer-app", reparsed.getString("name"))
        kotlin.test.assertEquals(
            "^1.0.0",
            reparsed.getJSONObject("popeDependencies").getString("example.calculator"),
        )
        kotlin.test.assertEquals("src", reparsed.getJSONArray("buildPath").getJSONObject(0).getString("path"))
    }
}
