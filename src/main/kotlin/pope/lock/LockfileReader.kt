package pope.lock

import org.json.JSONObject
import java.io.File

data class LockedPackage(val version: String, val integrity: String)

/** Reads pope.lock's existing "resolved" entries, keyed by package_name. */
object LockfileReader {
    fun read(file: File): Map<String, LockedPackage> {
        if (!file.exists()) return emptyMap()

        val resolved = JSONObject(file.readText()).optJSONObject("resolved") ?: return emptyMap()
        return resolved.keySet().associateWith { packageName ->
            val entry = resolved.getJSONObject(packageName)
            LockedPackage(version = entry.getString("version"), integrity = entry.getString("integrity"))
        }
    }
}
