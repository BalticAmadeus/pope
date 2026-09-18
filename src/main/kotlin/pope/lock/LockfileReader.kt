package pope.lock

import org.json.JSONObject
import java.io.File

data class LockedPackage(
    val version: String,
    val integrity: String,
    // Where this package landed under pope_packages/ - a registry's name or "dependencies"
    // (SharedRegistryRoot), or a per-package subpath (Isolated); null for older lockfiles
    // predating this field. Needed on uninstall/prune to find an already-removed dependency's own
    // install location without re-resolving it.
    val installSubpath: String? = null,
    // This package's own relative file paths within its install location - only meaningful for
    // SharedRegistryRoot packages (see InstallLayout), where install/uninstall/prune must touch
    // only these files, never the whole shared folder. Empty for Isolated packages and for older
    // lockfiles predating this field.
    val files: List<String> = emptyList(),
)

/** Reads pope.lock's existing "resolved" entries, keyed by package_name. */
object LockfileReader {
    fun read(file: File): Map<String, LockedPackage> {
        if (!file.exists()) return emptyMap()

        val resolved = JSONObject(file.readText()).optJSONObject("resolved") ?: return emptyMap()
        return resolved.keySet().associateWith { packageName ->
            val entry = resolved.getJSONObject(packageName)
            LockedPackage(
                version = entry.getString("version"),
                integrity = entry.getString("integrity"),
                installSubpath = entry.optString("installSubpath").takeIf { it.isNotBlank() },
                files = entry.optJSONArray("files")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
            )
        }
    }

    /**
     * Reads pope.lock's existing "trustedDirectSources" entries, keyed by
     * package_name, value "repoUrl@ref" - so an already-approved
     * direct-source dependency isn't re-prompted on every reinstall as
     * long as its repoUrl/ref hasn't changed.
     */
    fun readTrustedDirectSources(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()

        val trusted = JSONObject(file.readText()).optJSONObject("trustedDirectSources") ?: return emptyMap()
        return trusted.keySet().associateWith { trusted.getString(it) }
    }
}
