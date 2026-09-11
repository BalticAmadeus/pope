package pope.registry

import java.io.File

data class ResolvedPackage(
    val packageName: String,
    val version: String,
    val sourceDir: File,
    // The package's own project root, where openedge-project.json lives -
    // needed to read its own dependencies for transitive resolution.
    val projectDir: File,
    // Install-layout hint: where under pope_packages/ this lands (e.g.
    // "ba/calculator"). null = flat, use packageName directly. Cosmetic
    // only, never used for resolution/keying/collision-detection.
    val installSubpath: String? = null,
)

interface Registry {
    fun resolve(packageName: String, versionSpec: String): ResolvedPackage

    /** Like resolve, but picks any available version - for auto-picking one when the caller didn't specify. */
    fun findAny(packageName: String): ResolvedPackage?
}
