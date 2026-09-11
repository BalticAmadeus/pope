package pope.manifest

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Adds resolved dependencies' pope_packages paths to buildPath. Additive
 * only - never removes/reorders; that's pruneStalePopePackagesEntries'
 * job instead, used by popePrune.
 */
object BuildPathUpdater {
    fun ensureSourceEntries(manifestFile: File, paths: List<String>) {
        val json = JSONObject(manifestFile.readText())
        val buildPath = json.optJSONArray("buildPath") ?: JSONArray()

        val existingPaths =
            (0 until buildPath.length())
                .map { buildPath.getJSONObject(it) }
                .filter { it.optString("type") == "source" }
                .map { it.getString("path") }
                .toSet()

        var changed = false
        for (path in paths) {
            if (path !in existingPaths) {
                buildPath.put(JSONObject().put("type", "source").put("path", path))
                changed = true
            }
        }

        if (changed) {
            json.put("buildPath", buildPath)
            ManifestWriter.write(manifestFile, json)
        }
    }

    /** Removes stale "pope_packages/..." buildPath entries not in expectedPaths. dryRun previews without writing. */
    fun pruneStalePopePackagesEntries(manifestFile: File, expectedPaths: Set<String>, dryRun: Boolean = false): List<String> {
        val json = JSONObject(manifestFile.readText())
        val buildPath = json.optJSONArray("buildPath") ?: JSONArray()

        val entries = (0 until buildPath.length()).map { buildPath.getJSONObject(it) }
        val stale =
            entries.filter { entry ->
                entry.optString("type") == "source" &&
                    entry.optString("path").startsWith("pope_packages/") &&
                    entry.optString("path") !in expectedPaths
            }
        if (stale.isEmpty()) return emptyList()

        val removedPaths = stale.map { it.getString("path") }

        if (!dryRun) {
            val kept = JSONArray()
            entries.filter { it !in stale }.forEach { kept.put(it) }
            json.put("buildPath", kept)
            ManifestWriter.write(manifestFile, json)
        }

        return removedPaths
    }
}
