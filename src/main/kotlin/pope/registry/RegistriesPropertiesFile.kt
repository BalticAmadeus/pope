package pope.registry

import java.io.File
import java.util.Properties

data class RegistryFileEntry(
    val name: String,
    val prefix: String,
    val catalogUrl: String,
    val catalogRef: String?,
)

/**
 * A CLI-appendable alternative to registries{} (PopePlugin.kt), one
 * property per field, namespaced by registry name:
 *   ba.prefix=ba.
 *   ba.catalogUrl=https://github.com/erudys27/registry-ba.git
 */
object RegistriesPropertiesFile {
    fun read(file: File): List<RegistryFileEntry> {
        if (!file.exists()) return emptyList()

        val props = Properties()
        file.inputStream().use { props.load(it) }

        val names = props.stringPropertyNames().mapNotNull { it.removeSuffix(".prefix").takeIf { _ -> it.endsWith(".prefix") } }

        return names.sorted().map { name ->
            val prefix =
                props.getProperty("$name.prefix")
                    ?: throw IllegalStateException("${file.path}: \"$name\" is missing \"$name.prefix\"")
            val catalogUrl =
                props.getProperty("$name.catalogUrl")
                    ?: throw IllegalStateException("${file.path}: \"$name\" is missing \"$name.catalogUrl\"")
            RegistryFileEntry(name, prefix, catalogUrl, props.getProperty("$name.catalogRef"))
        }
    }

    /** Creates the file if missing. Appends only - never rewrites existing lines. */
    fun add(file: File, name: String, prefix: String, catalogUrl: String) {
        val existing = read(file)
        require(existing.none { it.name == name }) {
            "Registry \"$name\" is already declared in ${file.path}"
        }
        val prefixOwner = existing.firstOrNull { it.prefix == prefix }
        require(prefixOwner == null) {
            "Registry prefix \"$prefix\" is already declared in ${file.path} (as \"${prefixOwner?.name}\")"
        }

        val needsLeadingNewline = file.exists() && file.length() > 0 && !file.readText().endsWith("\n")
        file.appendText((if (needsLeadingNewline) "\n" else "") + "$name.prefix=$prefix\n$name.catalogUrl=$catalogUrl\n")
    }
}
