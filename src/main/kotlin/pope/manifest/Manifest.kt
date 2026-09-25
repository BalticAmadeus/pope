package pope.manifest

/**
 * A dependencies-map entry: Registry (a caret-range string, "^1.0.0",
 * name must be fully-qualified like "ba.greeter") or DirectSource (inline
 * {repoUrl, ref}, no registry lookup - see docs/spec/manifest-schema.md).
 */
sealed interface DependencySpec {
    data class Registry(val versionSpec: String) : DependencySpec

    data class DirectSource(val repoUrl: String, val ref: String) : DependencySpec
}

data class Manifest(
    val name: String,
    val version: String,
    val packageName: String,
    val dependencies: Map<String, DependencySpec> = emptyMap(),
    // buildPath "source" entries; sourceRoots.first() also serves as package_root.
    val sourceRoots: List<String> = emptyList(),
    // buildPath "test" entries. Only ever read for this project's own
    // PROPATH (PropathGenerator's includeTests) - never for a dependency,
    // so a dependency's tests can't leak into a consumer's PROPATH.
    val testRoots: List<String> = emptyList(),
    // Which pope version last wrote this manifest (ManifestWriter stamps it on every write) - null
    // for a manifest never written by a version that supports this, or applied via includeBuild.
    val popeToolVersion: String? = null,
)
