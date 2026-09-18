package pope.registry

import java.io.File

/**
 * How a resolved package lands under pope_packages/:
 *  - SharedRegistryRoot - copied directly into pope_packages/<installSubpath>/, alongside every
 *    other package sharing that same root. A package's own source root already mirrors its real
 *    package_name as nested folders (hard ABL/PROPATH requirement), so this needs no further
 *    per-package subfolder - org/package nesting comes for free from the package's own content,
 *    with no risk of doubling it. Used both by packages resolved from a registry (installSubpath =
 *    that registry's own name) and by every direct-source dependency (installSubpath =
 *    "dependencies", one shared root for all of them, regardless of which registry, if any, they
 *    were declared alongside). Multiple packages can share this root, so install/uninstall/prune
 *    must track and touch only each package's own files, never the whole folder.
 *  - Isolated - one dedicated pope_packages/<installSubpath ?: packageName>/src/ folder per
 *    package (the LocalDirectoryRegistry fallback only, today). Always safe to delete/recreate
 *    wholesale, since nothing else ever shares it.
 */
enum class InstallLayout { SharedRegistryRoot, Isolated }

data class ResolvedPackage(
    val packageName: String,
    val version: String,
    val sourceDir: File,
    // The package's own project root, where openedge-project.json lives -
    // needed to read its own dependencies for transitive resolution.
    val projectDir: File,
    // Install-layout hint: where under pope_packages/ this lands - a
    // registry's own name, or "dependencies" for direct-source
    // (SharedRegistryRoot), or a per-package subpath for Isolated. null =
    // flat, use packageName directly. Cosmetic only, never used for
    // resolution/keying/collision-detection.
    val installSubpath: String? = null,
    val installLayout: InstallLayout = InstallLayout.Isolated,
)

interface Registry {
    fun resolve(packageName: String, versionSpec: String): ResolvedPackage

    /** Like resolve, but picks any available version - for auto-picking one when the caller didn't specify. */
    fun findAny(packageName: String): ResolvedPackage?
}
