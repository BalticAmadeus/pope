package pope.registry

import java.io.File

/**
 * SharedRegistryRoot: pope_packages/<installSubpath>/, shared by every package from that root
 * (a registry's own name, or "dependencies" for direct-source). Safe because a package's own
 * source tree already mirrors its package_name (ABL/PROPATH requirement), so install/uninstall
 * must touch only each package's own files, never the whole folder.
 * Isolated: one dedicated pope_packages/<installSubpath ?: packageName>/src/ per package
 * (LocalDirectoryRegistry only) - always safe to delete/recreate wholesale.
 */
enum class InstallLayout { SharedRegistryRoot, Isolated }

data class ResolvedPackage(
    val packageName: String,
    val version: String,
    val sourceDir: File,
    val projectDir: File,
    val installSubpath: String? = null,
    val installLayout: InstallLayout = InstallLayout.Isolated,
)

interface Registry {
    fun resolve(packageName: String, versionSpec: String): ResolvedPackage

    /** Like resolve, but picks any available version. */
    fun findAny(packageName: String): ResolvedPackage?

    /** Cheap existence check - catalog/metadata only, must never fetch the real package content. */
    fun hasAny(packageName: String): Boolean = findAny(packageName) != null
}
