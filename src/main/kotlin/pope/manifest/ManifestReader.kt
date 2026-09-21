package pope.manifest

import org.json.JSONObject
import java.io.File

/** Reads pope's fields from openedge-project.json - no separate pope.json, see docs/spec/manifest-schema.md. */
object ManifestReader {
    fun read(file: File): Manifest {
        require(file.exists()) { "Manifest not found: ${file.path}" }
        val json = JSONObject(file.readText())

        fun rootsOfType(type: String): List<String> =
            json.optJSONArray("buildPath")?.let { entries ->
                (0 until entries.length()).mapNotNull { i ->
                    val entry = entries.getJSONObject(i)
                    entry.optString("path").takeIf { entry.optString("type") == type }
                }
            } ?: emptyList()

        val sourceRoots = rootsOfType("source")
        val testRoots = rootsOfType("test")

        val packageName =
            if (json.has("popePackageName")) {
                json.getString("popePackageName")
            } else {
                inferAndPersistPackageName(file, json, sourceRoots)
            }

        val dependencies =
            json.optJSONObject("popeDependencies")?.let { deps ->
                deps.keySet().associateWith { key -> parseDependencySpec(key, deps.get(key), file) }
            } ?: emptyMap()

        return Manifest(
            name = json.getString("name"),
            version = json.getString("version"),
            packageName = packageName,
            dependencies = dependencies,
            sourceRoots = sourceRoots,
            testRoots = testRoots,
        )
    }

    private fun parseDependencySpec(key: String, value: Any, file: File): DependencySpec =
        when (value) {
            is String -> DependencySpec.Registry(value)
            is JSONObject ->
                DependencySpec.DirectSource(
                    repoUrl =
                        value.optString("repoUrl").takeIf { it.isNotBlank() }
                            ?: throw IllegalArgumentException(
                                "Dependency \"$key\" in ${file.path} is missing \"repoUrl\"",
                            ),
                    ref =
                        value.optString("ref").takeIf { it.isNotBlank() }
                            ?: throw IllegalArgumentException("Dependency \"$key\" in ${file.path} is missing \"ref\""),
                )
            else ->
                throw IllegalArgumentException(
                    "Dependency \"$key\" in ${file.path} has an unrecognized shape - expected a version-range " +
                        "string or a {repoUrl, ref} object",
                )
        }

    /** Only runs when package_name is absent - infers it from .cls files and persists it, so this runs at most once. */
    private fun inferAndPersistPackageName(file: File, json: JSONObject, sourceRoots: List<String>): String {
        val packageRoot =
            sourceRoots.firstOrNull()
                ?: throw IllegalArgumentException(
                    "Missing required \"package_name\" in ${file.path}, and it can't be inferred: " +
                        "no buildPath source entry to scan for .cls files — see docs/spec/manifest-schema.md",
                )

        val inferred = PackageNameInferrer.infer(File(file.parentFile, packageRoot))

        json.put("popePackageName", inferred)
        ManifestWriter.write(file, json)

        return inferred
    }
}
