package pope

import pope.integrity.DirectoryHash
import pope.lock.IntegrityChecker
import pope.lock.LockfileReader
import pope.manifest.BuildPathUpdater
import pope.manifest.DependenciesUpdater
import pope.manifest.DependencySpec
import pope.manifest.ManifestReader
import pope.propath.PropathGenerator
import pope.registry.CatalogRegistry
import pope.registry.LocalDirectoryRegistry
import pope.registry.PrefixRoutingRegistry
import pope.registry.Registry
import pope.registry.RegistriesPropertiesFile
import pope.registry.RegistryEntry
import pope.registry.ResolvedPackage
import pope.resolver.DependencyResolver
import pope.suggest.NameNotFoundException
import pope.trust.TrustPrompt
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.userinput.UserInputHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** One registries{} entry. "name" is just its DSL label; "prefix" is the actual routing key (e.g. "ba."). */
abstract class GitRegistrySpec
    @Inject
    constructor(private val entryName: String) : Named {
        abstract val prefix: Property<String>
        abstract val catalogUrl: Property<String>
        abstract val catalogRef: Property<String>

        override fun getName() = entryName
    }

abstract class PopeExtension
    @Inject
    constructor(objects: ObjectFactory) {
        // Where the ABL project lives. Defaults to build.gradle.kts's own
        // directory; set to file("..") when Gradle's files sit in .pope/.
        abstract val projectRoot: DirectoryProperty
        abstract val registryRoot: DirectoryProperty
        abstract val cacheDir: DirectoryProperty

        val registries: NamedDomainObjectContainer<GitRegistrySpec> =
            objects.domainObjectContainer(GitRegistrySpec::class.java) { name ->
                objects.newInstance(GitRegistrySpec::class.java, name)
            }
    }

class PopePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("pope", PopeExtension::class.java)
        extension.projectRoot.convention(project.layout.projectDirectory)
        extension.registryRoot.convention(extension.projectRoot)
        extension.cacheDir.convention(
            project.layout.dir(
                project.provider {
                    File(
                        project.findProperty("popeCacheDir") as? String
                            ?: "${System.getProperty("user.home")}/.pope/cache",
                    )
                },
            ),
        )

        project.tasks.register("popeInstall") { task ->
            task.group = "pope"
            task.description =
                "Resolves the project's declared dependencies. " +
                "Pass -PpopeAdd=<package_name>[:<versionSpec>] to add and resolve a new dependency in one step - " +
                "a close-but-not-found name (typo) prompts to install the suggested one instead of just failing. " +
                "Direct-source dependencies (not from a registry) prompt for confirmation; " +
                "pass -PpopeTrustAll to approve them non-interactively (e.g. in CI)."
            task.doLast {
                project.logger.lifecycle("=== pope install ===")
                project.logger.lifecycle("")

                val projectRoot = extension.projectRoot.get().asFile
                val manifestFile = projectRoot.resolve("openedge-project.json")
                val registry = buildRegistry(extension)
                val manifest = ManifestReader.read(manifestFile)
                val userInputHandler = (project as ProjectInternal).services.get(UserInputHandler::class.java)

                // Held in memory, not written yet - keeps a failed resolve from touching the manifest at all.
                val pendingAdd: Pair<String, String>? =
                    if (project.hasProperty("popeAdd")) {
                        resolveAddSpec(project.property("popeAdd") as String, registry, userInputHandler)
                    } else {
                        null
                    }
                val dependenciesToResolve =
                    if (pendingAdd != null) {
                        val (packageName, versionSpec) = pendingAdd
                        manifest.dependencies + (packageName to DependencySpec.Registry(versionSpec))
                    } else {
                        manifest.dependencies
                    }

                // Direct-source deps bypass the registry catalog entirely, so each one not already
                // approved (same repoUrl@ref) in pope.lock gets a trust prompt before it's fetched -
                // see TrustPrompt. -PpopeTrustAll skips the prompt for non-interactive runs (CI).
                val existingTrust = LockfileReader.readTrustedDirectSources(projectRoot.resolve("pope.lock"))
                val trustAll = project.hasProperty("popeTrustAll")
                val newlyTrusted = LinkedHashMap<String, String>()
                val onDirectSource: (String, DependencySpec.DirectSource, List<String>) -> Unit = { packageKey, spec, path ->
                    val sourceKey = "${spec.repoUrl}@${spec.ref}"
                    if (existingTrust[packageKey] != sourceKey) {
                        val approved =
                            trustAll || TrustPrompt.confirm(userInputHandler, packageKey, spec.repoUrl, spec.ref, path)
                        check(approved) {
                            "pope install: \"$packageKey\" is a direct-source dependency, not from any " +
                                "configured registry (${spec.repoUrl}@${spec.ref}), and was not approved. " +
                                "Re-run interactively to approve it, or pass -PpopeTrustAll to approve " +
                                "non-interactively."
                        }
                    }
                    newlyTrusted[packageKey] = sourceKey
                }

                // Resolves the full graph, including transitive deps - see DependencyResolver.
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages =
                    DependencyResolver.resolveAll(dependenciesToResolve, registry, directSourceCacheDir, onDirectSource)

                // Catches a moved/hijacked tag before touching pope_packages/, not after.
                val existingLock = LockfileReader.read(projectRoot.resolve("pope.lock"))
                val integrities =
                    resolvedPackages.mapValues { (packageName, resolvedPackage) ->
                        val integrity = DirectoryHash.hash(resolvedPackage.sourceDir)
                        IntegrityChecker.verify(packageName, resolvedPackage.version, integrity, existingLock)
                        integrity
                    }

                val resolved =
                    resolvedPackages.mapValues { (packageName, resolvedPackage) ->
                        val destination =
                            projectRoot
                                .resolve("pope_packages")
                                .resolve(resolvedPackage.installSubpath ?: packageName)
                                .resolve("src")
                        destination.deleteRecursively()
                        resolvedPackage.sourceDir.copyRecursively(destination, overwrite = true)
                        resolvedPackage
                    }

                if (pendingAdd != null) {
                    val (packageName, versionSpec) = pendingAdd
                    DependenciesUpdater.addDependency(manifestFile, packageName, versionSpec)
                    project.logger.lifecycle("  + added \"$packageName\": \"$versionSpec\" to dependencies")
                }

                val resolvedJson = JSONObject()
                resolved.forEach { (packageName, resolvedPackage) ->
                    resolvedJson.put(
                        packageName,
                        JSONObject()
                            .put("version", resolvedPackage.version)
                            .put("source", resolvedPackage.sourceDir.absolutePath)
                            .put("integrity", integrities.getValue(packageName)),
                    )
                }
                val trustedDirectSourcesJson = JSONObject()
                newlyTrusted.forEach { (packageKey, sourceKey) -> trustedDirectSourcesJson.put(packageKey, sourceKey) }
                val lockJson =
                    JSONObject()
                        .put("resolved", resolvedJson)
                        .put("trustedDirectSources", trustedDirectSourcesJson)
                projectRoot.resolve("pope.lock").writeText(lockJson.toString(2))

                val dependencySourcePaths =
                    resolved.map { (packageName, resolvedPackage) ->
                        "pope_packages/${resolvedPackage.installSubpath ?: packageName}/src"
                    }
                BuildPathUpdater.ensureSourceEntries(manifestFile, dependencySourcePaths)

                // Only new/version-changed packages get listed individually - pope_packages/ already
                // holding an unchanged package isn't news on every reinstall, just noise.
                val newOrChangedPackages =
                    resolved.filterKeys { name -> existingLock[name]?.version != resolved.getValue(name).version }
                val dependencyWord = if (resolved.size == 1) "dependency" else "dependencies"
                if (newOrChangedPackages.isEmpty()) {
                    project.logger.lifecycle("\nAll ${resolved.size} $dependencyWord already up to date")
                } else {
                    val unchangedCount = resolved.size - newOrChangedPackages.size
                    val unchangedSuffix = if (unchangedCount > 0) " ($unchangedCount unchanged)" else ""
                    project.logger.lifecycle("\nResolved ${resolved.size} $dependencyWord$unchangedSuffix:")
                    newOrChangedPackages.keys.sorted().forEach { packageName ->
                        val previousVersion = existingLock[packageName]?.version
                        val currentVersion = newOrChangedPackages.getValue(packageName).version
                        if (previousVersion == null) {
                            project.logger.lifecycle("  + $packageName ($currentVersion)")
                        } else {
                            project.logger.lifecycle("  ~ $packageName ($previousVersion -> $currentVersion)")
                        }
                    }
                }
            }
        }

        project.tasks.register("popePropath") { task ->
            task.group = "pope"
            task.description =
                "Prints the generated PROPATH for the project. " +
                "Pass -PpopeIncludeTests to also include buildPath's \"test\" entries."
            task.doLast {
                val projectRoot = extension.projectRoot.get().asFile
                val manifest = ManifestReader.read(projectRoot.resolve("openedge-project.json"))
                val includeTests = project.hasProperty("popeIncludeTests")
                val propath = PropathGenerator.generate(projectRoot, manifest, includeTests)
                project.logger.lifecycle(propath.joinToString(System.lineSeparator()))
            }
        }

        project.tasks.register("popePrune") { task ->
            task.group = "pope"
            task.description =
                "Removes pope_packages/ entries (and their buildPath references) that are no longer " +
                "part of the resolved dependency graph. Pass -PpopeDryRun to preview without changing anything."
            task.doLast {
                project.logger.lifecycle("=== pope prune ===")
                project.logger.lifecycle("")

                val projectRoot = extension.projectRoot.get().asFile
                val manifestFile = projectRoot.resolve("openedge-project.json")
                val registry = buildRegistry(extension)
                val manifest = ManifestReader.read(manifestFile)

                // Same resolution popeInstall does, to know what "stale" actually means right now.
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages = DependencyResolver.resolveAll(manifest.dependencies, registry, directSourceCacheDir)
                val expectedPaths =
                    resolvedPackages.map { (packageName, resolvedPackage) ->
                        "pope_packages/${resolvedPackage.installSubpath ?: packageName}/src"
                    }.toSet()

                val dryRun = project.hasProperty("popeDryRun")

                val staleDirs = findStalePopePackagesDirs(projectRoot, expectedPaths)
                if (!dryRun) {
                    val popePackagesDir = projectRoot.resolve("pope_packages")
                    staleDirs.forEach { (leafDir, _) ->
                        leafDir.deleteRecursively()
                        removeNowEmptyAncestors(leafDir.parentFile, popePackagesDir)
                    }
                }
                val staleBuildPathPaths = BuildPathUpdater.pruneStalePopePackagesEntries(manifestFile, expectedPaths, dryRun)

                val allStalePaths = (staleDirs.map { it.second } + staleBuildPathPaths).toSortedSet()
                if (allStalePaths.isEmpty()) {
                    project.logger.lifecycle("  nothing to remove")
                } else {
                    val verb = if (dryRun) "would remove" else "removed"
                    allStalePaths.forEach { project.logger.lifecycle("  - $verb $it") }
                    val entryWord = if (allStalePaths.size == 1) "entry" else "entries"
                    val suffix = if (dryRun) " (dry run - nothing changed)" else ""
                    project.logger.lifecycle("")
                    project.logger.lifecycle("${allStalePaths.size} $entryWord $verb$suffix")
                }
            }
        }

        project.tasks.register("popeUninstall") { task ->
            task.group = "pope"
            task.description =
                "Removes a dependency and cleans up its pope_packages/pope.lock/buildPath entries. " +
                "Usage: -PpopeUninstall=<package_name>"
            task.doLast {
                project.logger.lifecycle("=== pope uninstall ===")
                project.logger.lifecycle("")

                val packageName =
                    project.findProperty("popeUninstall") as String?
                        ?: throw GradleException("Missing -PpopeUninstall=<package_name>.")

                val projectRoot = extension.projectRoot.get().asFile
                val manifestFile = projectRoot.resolve("openedge-project.json")
                val registry = buildRegistry(extension)
                val manifest = ManifestReader.read(manifestFile)

                require(packageName in manifest.dependencies) {
                    "\"$packageName\" is not declared in dependencies - nothing to uninstall."
                }

                // Re-resolves what's left, same as popePrune, to know exactly
                // what should still exist (including anything that was only
                // pulled in transitively by the package being removed).
                val remainingDependencies = manifest.dependencies - packageName
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages = DependencyResolver.resolveAll(remainingDependencies, registry, directSourceCacheDir)
                val expectedPaths =
                    resolvedPackages.map { (name, resolvedPackage) ->
                        "pope_packages/${resolvedPackage.installSubpath ?: name}/src"
                    }.toSet()

                val popePackagesDir = projectRoot.resolve("pope_packages")
                val staleDirs = findStalePopePackagesDirs(projectRoot, expectedPaths)
                staleDirs.forEach { (leafDir, _) ->
                    leafDir.deleteRecursively()
                    removeNowEmptyAncestors(leafDir.parentFile, popePackagesDir)
                }
                BuildPathUpdater.pruneStalePopePackagesEntries(manifestFile, expectedPaths)
                DependenciesUpdater.removeDependency(manifestFile, packageName)

                // Reuses each remaining package's already-known integrity
                // rather than re-hashing - only falls back to a fresh hash
                // if pope.lock didn't already have an entry for it.
                val existingLock = LockfileReader.read(projectRoot.resolve("pope.lock"))
                val resolvedJson = JSONObject()
                resolvedPackages.forEach { (name, resolvedPackage) ->
                    val integrity = existingLock[name]?.integrity ?: DirectoryHash.hash(resolvedPackage.sourceDir)
                    resolvedJson.put(
                        name,
                        JSONObject()
                            .put("version", resolvedPackage.version)
                            .put("source", resolvedPackage.sourceDir.absolutePath)
                            .put("integrity", integrity),
                    )
                }
                projectRoot.resolve("pope.lock").writeText(JSONObject().put("resolved", resolvedJson).toString(2))

                project.logger.lifecycle("  removed \"$packageName\" (${staleDirs.size} package folder(s) cleaned up)")
            }
        }

        project.tasks.register("popeRegistryAdd") { task ->
            task.group = "pope"
            task.description =
                "Adds a registry entry to pope-registries.properties. " +
                "Usage: -PregistryPrefix=<prefix> -PcatalogUrl=<url> [-PregistryName=<name>]"
            task.doLast {
                project.logger.lifecycle("=== pope registry add ===")
                project.logger.lifecycle("")

                val prefix =
                    project.findProperty("registryPrefix") as String?
                        ?: throw GradleException(
                            "Missing -PregistryPrefix=<prefix>. Usage: -PregistryPrefix=<prefix> " +
                                "-PcatalogUrl=<url> [-PregistryName=<name>]",
                        )
                val catalogUrl =
                    project.findProperty("catalogUrl") as String?
                        ?: throw GradleException(
                            "Missing -PcatalogUrl=<url>. Usage: -PregistryPrefix=<prefix> " +
                                "-PcatalogUrl=<url> [-PregistryName=<name>]",
                        )
                val name = project.findProperty("registryName") as String? ?: prefix.trimEnd('.')

                val file = extension.projectRoot.get().asFile.resolve("pope-registries.properties")
                val storedPrefix = RegistriesPropertiesFile.add(file, name, prefix, catalogUrl)
                project.logger.lifecycle("  + added \"$name\" ($storedPrefix -> $catalogUrl) to ${file.name}")
            }
        }
    }
}

/**
 * Merges registries{} (build.gradle.kts) with pope-registries.properties
 * (the programmatically-appendable source - see RegistriesPropertiesFile).
 * A prefix OR a name declared twice, in either source, is an error, not a
 * pick - name uniqueness matters because a dependency key can address a
 * registry directly by name ("registryName/localName", see
 * PrefixRoutingRegistry), not just by prefix.
 * Falls back to LocalDirectoryRegistry only if both sources are empty.
 */
private fun buildRegistry(extension: PopeExtension): Registry {
    val fileEntries = RegistriesPropertiesFile.read(extension.projectRoot.get().asFile.resolve("pope-registries.properties"))

    if (extension.registries.isEmpty() && fileEntries.isEmpty()) {
        return LocalDirectoryRegistry(extension.registryRoot.get().asFile)
    }

    val cacheRoot = extension.cacheDir.get().asFile
    val ownerByPrefix = LinkedHashMap<String, String>()
    val ownerByName = LinkedHashMap<String, String>()
    val entries = mutableListOf<RegistryEntry>()

    fun addEntry(name: String, prefix: String, catalogUrl: String, catalogRef: String) {
        val existingPrefixOwner = ownerByPrefix[prefix]
        require(existingPrefixOwner == null) {
            "Duplicate registry prefix \"$prefix\": both \"$existingPrefixOwner\" and \"$name\" declare it"
        }
        ownerByPrefix[prefix] = name

        val existingNameOwner = ownerByName[name]
        require(existingNameOwner == null) {
            "Duplicate registry name \"$name\": both prefix \"$existingNameOwner\" and \"$prefix\" declare it"
        }
        ownerByName[name] = prefix

        entries +=
            RegistryEntry(
                name = name,
                prefix = prefix,
                registry =
                    CatalogRegistry(
                        registryName = name,
                        prefix = prefix,
                        catalogUrl = catalogUrl,
                        catalogRef = catalogRef,
                        cacheDir = File(cacheRoot, name),
                    ),
            )
    }

    for (spec in extension.registries) {
        addEntry(spec.name, spec.prefix.get(), spec.catalogUrl.get(), spec.catalogRef.getOrElse("main"))
    }
    for (entry in fileEntries) {
        addEntry(entry.name, entry.prefix, entry.catalogUrl, entry.catalogRef ?: "main")
    }

    return PrefixRoutingRegistry(entries)
}

/** Every "src" dir under pope_packages/ not in expectedPaths, paired with its parent (the whole package folder to delete). */
private fun findStalePopePackagesDirs(projectRoot: File, expectedPaths: Set<String>): List<Pair<File, String>> {
    val popePackagesDir = projectRoot.resolve("pope_packages")
    if (!popePackagesDir.isDirectory) return emptyList()

    return popePackagesDir
        .walkTopDown()
        .filter { it.isDirectory && it.name == "src" }
        .mapNotNull { srcDir ->
            val relativePath = srcDir.relativeTo(projectRoot).invariantSeparatorsPath
            if (relativePath in expectedPaths) null else srcDir.parentFile to relativePath
        }.toList()
}

/** Deletes now-empty ancestor dirs (e.g. an emptied-out registry-prefix folder), stopping at stopAt or the first non-empty one. */
private fun removeNowEmptyAncestors(dir: File, stopAt: File) {
    var current = dir
    while (current.absolutePath != stopAt.absolutePath && current.isDirectory && current.listFiles().isNullOrEmpty()) {
        val parent = current.parentFile
        current.delete()
        current = parent
    }
}

/** Parses -PpopeAdd=<name>[:<versionSpec>]; no versionSpec means "whatever's available", pinned as ^version. */
private fun resolveAddSpec(addSpec: String, registry: Registry, userInputHandler: UserInputHandler): Pair<String, String> {
    val separatorIndex = addSpec.indexOf(':')
    if (separatorIndex >= 0) {
        return addSpec.substring(0, separatorIndex) to addSpec.substring(separatorIndex + 1)
    }

    val (packageName, found) = findAnyConfirmingTypo(addSpec, registry, userInputHandler)
    return packageName to "^${found.version}"
}

/**
 * findAny(), but a "did you mean X?" suggestion (NameNotFoundException.suggestion) becomes an
 * actual yes/no prompt instead of just failing: "yes" retries with (and installs) the suggested
 * name instead, "no" - or nothing to suggest - fails exactly as before.
 */
private fun findAnyConfirmingTypo(
    packageName: String,
    registry: Registry,
    userInputHandler: UserInputHandler,
): Pair<String, ResolvedPackage> {
    val found =
        try {
            // findAny() returning null carries no detail (no catalog/registry context to build a
            // "did you mean X?" message from here) - re-resolve with a versionSpec no real
            // package satisfies purely to surface the registry's own richer error instead.
            registry.findAny(packageName) ?: registry.resolve(packageName, "^0.0.0")
        } catch (e: NameNotFoundException) {
            val suggestion = e.suggestion ?: throw e
            // e.message already ends in "- did you mean \"X\"?" - a complete yes/no question on
            // its own, so it's asked as-is rather than echoing the (full retry) suggestion again
            // in a separate line, which would re-state the untouched half as if newly confirmed.
            val approved = userInputHandler.askYesNoQuestion(e.message ?: "Install the suggested name instead?") ?: false
            if (!approved) throw e
            return findAnyConfirmingTypo(suggestion, registry, userInputHandler)
        }
    return packageName to found
}
