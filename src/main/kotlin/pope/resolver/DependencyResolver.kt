package pope.resolver

import pope.fetch.GitPackageFetcher
import pope.manifest.DependencySpec
import pope.manifest.ManifestReader
import pope.registry.InstallLayout
import pope.registry.Registry
import pope.registry.ResolvedPackage
import pope.version.CaretRange
import pope.version.SemVer
import java.io.File

/**
 * Every direct-source dependency shares this one pope_packages/ root, same as packages sharing a
 * registry's own root (InstallLayout.SharedRegistryRoot) - each one's own source tree already
 * mirrors its real package_name as nested folders (the same ABL/PROPATH requirement that makes a
 * registry's shared root work), so no per-dependency subfolder is needed here either.
 */
private const val DIRECT_SOURCE_INSTALL_SUBPATH = "dependencies"

/**
 * Resolves a manifest's dependencies transitively, reading each resolved
 * package's own openedge-project.json until the whole graph is flat.
 *
 * DirectSource deps are always keyed by their own bare name, never an
 * inherited registry prefix - inheriting one was tried and dropped: two
 * differently-routed parents depending on the same repoUrl/ref would then
 * get different keys for what's really one shared dependency, falsely
 * tripping checkNoNamespaceCollision. Bare keys dedupe them correctly via
 * the repoUrl/ref check below instead.
 *
 * Exactly one resolution per key across the graph - any incompatible
 * second requirement (version, repoUrl/ref, or a Registry/DirectSource
 * mix) is an error, not a silent pick. A circular dependency is too.
 *
 * A resolved package's real namespace is independent of the key it was
 * resolved under, so two keys can still collide on the same real
 * namespace and silently shadow each other on PROPATH - caught by
 * checkNoNamespaceCollision below once the whole graph is known.
 */
object DependencyResolver {
    /** No-op default: callers that don't care about direct-source trust (e.g. most tests) see no behavior change. */
    private val noOpTrustGate: (String, DependencySpec.DirectSource, List<String>) -> Unit = { _, _, _ -> }

    fun resolveAll(
        rootDependencies: Map<String, DependencySpec>,
        registry: Registry,
        directSourceCacheDir: File,
        onDirectSource: (packageKey: String, spec: DependencySpec.DirectSource, path: List<String>) -> Unit = noOpTrustGate,
    ): Map<String, ResolvedPackage> {
        val resolved = LinkedHashMap<String, ResolvedPackage>()
        val resolvedSpecs = HashMap<String, DependencySpec>()
        val namespaceByKey = HashMap<String, String>()

        for ((packageName, spec) in rootDependencies) {
            resolveOne(
                packageName,
                spec,
                path = emptyList(),
                resolved,
                resolvedSpecs,
                namespaceByKey,
                registry,
                directSourceCacheDir,
                onDirectSource,
            )
        }

        checkNoNamespaceCollision(namespaceByKey)

        return resolved
    }

    private fun resolveOne(
        packageKey: String,
        spec: DependencySpec,
        path: List<String>,
        resolved: MutableMap<String, ResolvedPackage>,
        resolvedSpecs: MutableMap<String, DependencySpec>,
        namespaceByKey: MutableMap<String, String>,
        registry: Registry,
        directSourceCacheDir: File,
        onDirectSource: (packageKey: String, spec: DependencySpec.DirectSource, path: List<String>) -> Unit,
    ) {
        check(packageKey !in path) {
            "Circular dependency: ${(path + packageKey).joinToString(" -> ")}"
        }

        val existing = resolved[packageKey]
        if (existing != null) {
            checkNoConflict(packageKey, spec, existing, resolvedSpecs.getValue(packageKey), path)
            return
        }

        val resolvedPackage =
            when (spec) {
                is DependencySpec.Registry -> registry.resolve(packageKey, spec.versionSpec)
                is DependencySpec.DirectSource -> {
                    onDirectSource(packageKey, spec, path)
                    GitPackageFetcher
                        .fetch(packageKey, spec.repoUrl, spec.ref, File(directSourceCacheDir, packageKey))
                        .copy(installSubpath = DIRECT_SOURCE_INSTALL_SUBPATH, installLayout = InstallLayout.SharedRegistryRoot)
                }
            }
        resolved[packageKey] = resolvedPackage
        resolvedSpecs[packageKey] = spec

        val ownManifestFile = resolvedPackage.projectDir.resolve("openedge-project.json")
        if (ownManifestFile.exists()) {
            val ownManifest = ManifestReader.read(ownManifestFile)
            resolved[packageKey] = resolvedPackage.copy(popeToolVersion = ownManifest.popeToolVersion)
            namespaceByKey[packageKey] = ownManifest.packageName
            for ((depName, depSpec) in ownManifest.dependencies) {
                resolveOne(
                    depName,
                    depSpec,
                    path + packageKey,
                    resolved,
                    resolvedSpecs,
                    namespaceByKey,
                    registry,
                    directSourceCacheDir,
                    onDirectSource,
                )
            }
        }
    }

    /** Fails loudly if two keys share a real package_name (see class doc), before anything is copied into pope_packages/. */
    private fun checkNoNamespaceCollision(namespaceByKey: Map<String, String>) {
        val keysByNamespace = namespaceByKey.entries.groupBy({ it.value }, { it.key })
        for ((namespace, keys) in keysByNamespace) {
            check(keys.size == 1) {
                "PROPATH namespace collision: ${keys.sorted().joinToString(" and ")} both declare the same " +
                    "OO ABL namespace \"$namespace\" in their own package_name. Only one would actually be " +
                    "reachable on PROPATH (whichever comes first), silently shadowing the other. Rename one " +
                    "package's own package_name/namespace so they no longer collide."
            }
        }
    }

    private fun checkNoConflict(
        packageKey: String,
        spec: DependencySpec,
        existing: ResolvedPackage,
        existingSpec: DependencySpec,
        path: List<String>,
    ) {
        val via = (path + packageKey).joinToString(" -> ")
        when {
            spec is DependencySpec.Registry && existingSpec is DependencySpec.Registry -> {
                val existingVersion = SemVer.parse(existing.version)
                check(CaretRange.satisfies(spec.versionSpec, existingVersion)) {
                    "Version conflict for \"$packageKey\": already resolved to ${existing.version}, " +
                        "which does not satisfy ${spec.versionSpec} required via $via"
                }
            }
            spec is DependencySpec.DirectSource && existingSpec is DependencySpec.DirectSource -> {
                check(spec.repoUrl == existingSpec.repoUrl && spec.ref == existingSpec.ref) {
                    "Conflicting direct-source dependency for \"$packageKey\": already resolved from " +
                        "${existingSpec.repoUrl}@${existingSpec.ref}, but $via requires ${spec.repoUrl}@${spec.ref}"
                }
            }
            else ->
                throw IllegalStateException(
                    "Conflicting dependency kinds for \"$packageKey\": resolved once as a registry " +
                        "dependency and once as a direct-source dependency (via $via) — these must refer to " +
                        "the same kind of dependency.",
                )
        }
    }
}
