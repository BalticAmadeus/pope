package pope.registry

import pope.fetch.GitCli
import pope.fetch.GitPackageFetcher
import pope.suggest.DidYouMean
import pope.suggest.NameNotFoundException
import pope.version.CaretRange
import pope.version.SemVer
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Registry backed by a small "catalog" git repo holding no package
 * content, only one reference file per package version
 * (packages/<local_name>/<version>.json) pointing at that package's real
 * repo + tag. Fetching goes through GitPackageFetcher's cache.
 *
 * "local_name" = packageName with the registry's prefix stripped - just a
 * lookup key, not part of the package's real identity.
 *
 * A package's folder can hold multiple version files: resolve(versionSpec)
 * picks the highest satisfying the caret range, findAny picks the highest
 * overall - only the one picked ever gets fetched.
 *
 * Cache layout under cacheDir:
 *   _catalog/                 full clone of the catalog repo
 *   <local_name>/_bare.git/   bare clone of the package's repo
 *   <local_name>/<ref>/       worktree checkout of the version used
 */
class CatalogRegistry(
    private val registryName: String,
    private val prefix: String,
    private val catalogUrl: String,
    private val catalogRef: String,
    private val cacheDir: File,
) : Registry {
    private val catalogDir = File(cacheDir, "_catalog")

    override fun resolve(packageName: String, versionSpec: String): ResolvedPackage {
        val localName = localNameOf(packageName)
        ensureCatalogCloned()

        val references = findAllReferences(localName)
        if (references.isEmpty()) {
            val suggestion = DidYouMean.suggest(localName, allLocalNames())
            throw NameNotFoundException(
                "No package named \"$packageName\" found in registry \"$registryName\" catalog ($catalogUrl)" +
                    (suggestion?.let { " - did you mean \"$it\"?" } ?: ""),
                suggestion = suggestion?.let { "$registryName/$it" },
            )
        }

        val best =
            references
                .filter { CaretRange.satisfies(versionSpec, SemVer.parse(it.version)) }
                .maxByOrNull { SemVer.parse(it.version) }
                ?: throw IllegalStateException(
                    "Found \"$packageName\" in registry \"$registryName\", but none of its available " +
                        "versions (${references.joinToString(", ") { it.version }}) satisfy $versionSpec",
                )

        return fetchAndBuild(packageName, localName, best)
    }

    override fun findAny(packageName: String): ResolvedPackage? {
        val localName = localNameOf(packageName)
        ensureCatalogCloned()

        val references = findAllReferences(localName)
        val best = references.maxByOrNull { SemVer.parse(it.version) } ?: return null

        return fetchAndBuild(packageName, localName, best)
    }

    override fun hasAny(packageName: String): Boolean {
        val localName = localNameOf(packageName)
        ensureCatalogCloned()
        return findAllReferences(localName).isNotEmpty()
    }

    private fun localNameOf(packageName: String): String {
        require(packageName.startsWith(prefix)) {
            "\"$packageName\" doesn't start with registry \"$registryName\"'s configured prefix \"$prefix\" " +
                "— this registry should only ever be asked about names PrefixRoutingRegistry already routed to it"
        }
        return packageName.removePrefix(prefix)
    }

    private fun fetchAndBuild(packageName: String, localName: String, reference: PackageReference): ResolvedPackage {
        val packageDir = File(cacheDir, localName)
        val fetched = GitPackageFetcher.fetch(packageName, reference.repoUrl, reference.ref, packageDir)
        // installSubpath is this registry's own name, not its prefix - every package resolved from
        // here shares one pope_packages/<registryName>/ root (see InstallLayout.SharedRegistryRoot).
        return fetched.copy(installSubpath = registryName, installLayout = InstallLayout.SharedRegistryRoot)
    }

    /** All parsed version references for a package; empty if it isn't in this catalog. */
    private fun findAllReferences(localName: String): List<PackageReference> {
        val packageDir = File(catalogDir, "packages/$localName")
        if (!packageDir.isDirectory) return emptyList()

        val versionFiles = packageDir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        return versionFiles.map { readReference(it, localName) }
    }

    /** Every local_name this catalog has at least one version file for - for "did you mean X?" suggestions only. */
    private fun allLocalNames(): List<String> =
        File(catalogDir, "packages").listFiles { file -> file.isDirectory }.orEmpty().map { it.name }

    private fun ensureCatalogCloned() {
        if (File(catalogDir, ".git").exists()) {
            GitCli.run(catalogDir, "fetch", "origin", catalogRef)
            GitCli.run(catalogDir, "reset", "--hard", "FETCH_HEAD")
            return
        }

        cacheDir.mkdirs()
        GitCli.run(null, "clone", "--branch", catalogRef, catalogUrl, catalogDir.path)
    }

    private data class PackageReference(val repoUrl: String, val version: String, val ref: String)

    private fun readReference(file: File, localName: String): PackageReference {
        val json =
            try {
                JSONObject(file.readText())
            } catch (e: JSONException) {
                throw IllegalStateException("Malformed catalog reference file for \"$localName\": ${file.path}", e)
            }

        val repoUrl =
            json.optString("repoUrl").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"repoUrl\"")
        val version =
            json.optString("version").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"version\"")
        val ref =
            json.optString("ref").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"ref\"")

        return PackageReference(repoUrl, version, ref)
    }
}
