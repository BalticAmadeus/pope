package pope.manifest

import org.json.JSONObject
import java.io.File

/** Adds/removes one entry in openedge-project.json's dependencies - what -PpopeAdd/popeUninstall use instead of a manual edit. */
object DependenciesUpdater {
    fun addDependency(manifestFile: File, packageName: String, versionSpec: String) {
        val json = JSONObject(manifestFile.readText())
        val dependencies = json.optJSONObject("popeDependencies") ?: JSONObject()
        dependencies.put(packageName, versionSpec)
        json.put("popeDependencies", dependencies)
        ManifestWriter.write(manifestFile, json)
    }

    fun removeDependency(manifestFile: File, packageName: String) {
        val json = JSONObject(manifestFile.readText())
        val dependencies = json.optJSONObject("popeDependencies") ?: JSONObject()
        dependencies.remove(packageName)
        json.put("popeDependencies", dependencies)
        ManifestWriter.write(manifestFile, json)
    }
}
