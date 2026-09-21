package pope.manifest

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringWriter

/**
 * Writes openedge-project.json in a fixed key order - org.json's
 * JSONObject is HashMap-backed, so it won't serialize in insertion order
 * on its own. Every pope write path routes through here. Purely cosmetic.
 */
object ManifestWriter {
    private val canonicalKeyOrder = listOf("name", "version", "oeversion", "popePackageName", "popeDependencies", "buildPath")
    private const val indentFactor = 2

    fun write(file: File, json: JSONObject) {
        val orderedKeys = canonicalKeyOrder.filter { json.has(it) } + json.keySet().filter { it !in canonicalKeyOrder }

        val writer = StringWriter()
        writer.write("{\n")
        orderedKeys.forEachIndexed { index, key ->
            writer.write(" ".repeat(indentFactor))
            writer.write(JSONObject.quote(key))
            writer.write(": ")
            writeValue(writer, json.get(key))
            writer.write(if (index != orderedKeys.lastIndex) ",\n" else "\n")
        }
        writer.write("}\n")

        file.writeText(writer.toString())
    }

    // Starting indent at indentFactor keeps a nested object/array at the right depth, not restarting at 0.
    private fun writeValue(writer: StringWriter, value: Any) {
        when (value) {
            is JSONObject -> value.write(writer, indentFactor, indentFactor)
            is JSONArray -> value.write(writer, indentFactor, indentFactor)
            else -> writer.write(JSONObject.valueToString(value))
        }
    }
}
