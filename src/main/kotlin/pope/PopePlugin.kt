package pope

import pope.integrity.DirectoryHash
import pope.lock.IntegrityChecker
import pope.lock.LockedPackage
import pope.lock.LockfileReader
import pope.manifest.BuildPathUpdater
import pope.manifest.DependenciesUpdater
import pope.manifest.DependencySpec
import pope.manifest.ManifestReader
import pope.manifest.ManifestWriter
import pope.manifest.PackageNameInferrer
import pope.propath.PropathGenerator
import pope.registry.CatalogRegistry
import pope.registry.InstallLayout
import pope.registry.LocalDirectoryRegistry
import pope.registry.PrefixRoutingRegistry
import pope.registry.Registry
import pope.registry.RegistriesPropertiesFile
import pope.registry.RegistryEntry
import pope.registry.ResolvedPackage
import pope.resolver.DependencyResolver
import pope.suggest.DidYouMean
import pope.suggest.NameNotFoundException
import pope.suggest.NoRegistryPrefixMatchException
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
import org.json.JSONArray
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
                "a close-but-not-found name (typo) prompts to install the suggested one instead of just failing, " +
                "and a bare name matching no configured registry prefix searches every registry, prompting to " +
                "choose if more than one has it. Direct-source dependencies (not from a registry) prompt for " +
                "confirmation; pass -PpopeTrustAll to approve them non-interactively (e.g. in CI)."
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

                // Warn-only for now - v1 of this check is unproven enough that a false positive
                // shouldn't be able to block an install.
                val installedVersion = PopeVersion.current()
                resolvedPackages.forEach { (packageName, resolvedPackage) ->
                    if (PopeVersion.majorMismatch(installedVersion, resolvedPackage.popeToolVersion)) {
                        project.logger.warn(
                            "  ! \"$packageName\" was written by pope ${resolvedPackage.popeToolVersion}, but you're " +
                                "running pope $installedVersion - a major-version difference can mean an incompatible " +
                                "manifest shape or behavior. Proceeding anyway.",
                        )
                    }
                }

                // Catches a moved/hijacked tag before touching pope_packages/, not after.
                val existingLock = LockfileReader.read(projectRoot.resolve("pope.lock"))
                val integrities =
                    resolvedPackages.mapValues { (packageName, resolvedPackage) ->
                        val integrity = DirectoryHash.hash(resolvedPackage.sourceDir)
                        IntegrityChecker.verify(packageName, resolvedPackage.version, integrity, existingLock)
                        integrity
                    }

                val popePackagesDir = projectRoot.resolve("pope_packages")
                val installedFiles = LinkedHashMap<String, List<String>>()
                val resolved =
                    resolvedPackages.mapValues { (packageName, resolvedPackage) ->
                        installedFiles[packageName] =
                            installPackage(popePackagesDir, packageName, resolvedPackage, existingLock[packageName]?.files.orEmpty())
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
                            .put("integrity", integrities.getValue(packageName))
                            .putOpt("installSubpath", resolvedPackage.installSubpath)
                            .put("files", JSONArray(installedFiles.getValue(packageName))),
                    )
                }
                val trustedDirectSourcesJson = JSONObject()
                newlyTrusted.forEach { (packageKey, sourceKey) -> trustedDirectSourcesJson.put(packageKey, sourceKey) }
                val lockJson =
                    JSONObject()
                        .put("resolved", resolvedJson)
                        .put("trustedDirectSources", trustedDirectSourcesJson)
                projectRoot.resolve("pope.lock").writeText(lockJson.toString(2))

                val dependencySourcePaths = resolved.map { (packageName, resolvedPackage) -> buildPathEntryFor(packageName, resolvedPackage) }
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

        project.tasks.register("popeStamp") { task ->
            task.group = "pope"
            task.description =
                "Refreshes popeToolVersion on openedge-project.json, touching nothing else - for a " +
                "package with nothing else needing a patch (e.g. a leaf dependency with no deps of its " +
                "own), popeInit wouldn't otherwise write anything. Point pope.projectRoot at the " +
                "package's own checkout - no Gradle wiring needs to live in that repo at all."
            task.doLast {
                val manifestFile = extension.projectRoot.get().asFile.resolve("openedge-project.json")
                require(manifestFile.exists()) { "No openedge-project.json found at ${manifestFile.path}" }
                ManifestWriter.write(manifestFile, JSONObject(manifestFile.readText()))
                project.logger.lifecycle("  refreshed popeToolVersion in ${manifestFile.path}")
            }
        }

        project.tasks.register("popeVersion") { task ->
            task.group = "pope"
            task.description = "Prints the installed pope plugin version."
            task.doLast {
                val version = PopeVersion.current() ?: "unknown (not applied from a published version)"
                // quiet, not lifecycle - survives -q (see pope.bat's "version" subcommand).
                project.logger.quiet("")
                project.logger.quiet(version)
                project.logger.quiet("")
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

                // Old lock entries for packages no longer in the resolved graph - the only way to
                // find a removed package's own install location (registry root + its tracked files,
                // or its isolated folder) without re-resolving something that's gone.
                val existingLock = LockfileReader.read(projectRoot.resolve("pope.lock"))

                // Same resolution popeInstall does, to know what "stale" actually means right now.
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages = DependencyResolver.resolveAll(manifest.dependencies, registry, directSourceCacheDir)
                val expectedPaths = resolvedPackages.map { (name, resolvedPackage) -> buildPathEntryFor(name, resolvedPackage) }.toSet()

                val dryRun = project.hasProperty("popeDryRun")

                val stalePackageKeys = existingLock.keys - resolvedPackages.keys
                if (!dryRun) {
                    val popePackagesDir = projectRoot.resolve("pope_packages")
                    stalePackageKeys.forEach { packageKey ->
                        val affectedDirs = deleteInstalledPackage(popePackagesDir, packageKey, existingLock.getValue(packageKey))
                        affectedDirs.forEach { removeNowEmptyAncestors(it, popePackagesDir) }
                    }
                }
                val staleBuildPathPaths = BuildPathUpdater.pruneStalePopePackagesEntries(manifestFile, expectedPaths, dryRun)

                val allStalePaths = (stalePackageKeys + staleBuildPathPaths).toSortedSet()
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
                "Usage: -PpopeUninstall=<package_name> - a bare local name matching more than one " +
                "declared \"registryName/localName\" dependency prompts to choose which one, and a " +
                "close-but-not-declared name (typo) prompts to uninstall the suggested one instead."
            task.doLast {
                project.logger.lifecycle("=== pope uninstall ===")
                project.logger.lifecycle("")

                val uninstallSpec =
                    project.findProperty("popeUninstall") as String?
                        ?: throw GradleException("Missing -PpopeUninstall=<package_name>.")

                val projectRoot = extension.projectRoot.get().asFile
                val manifestFile = projectRoot.resolve("openedge-project.json")
                val registry = buildRegistry(extension)
                val manifest = ManifestReader.read(manifestFile)
                val userInputHandler = (project as ProjectInternal).services.get(UserInputHandler::class.java)

                val packageName = resolveUninstallSpec(uninstallSpec, manifest.dependencies.keys, userInputHandler)

                // Old lock entry for the package being removed - the only way to find its own
                // install location (registry root + its tracked files, or its isolated folder)
                // without re-resolving something that's about to be gone.
                val existingLock = LockfileReader.read(projectRoot.resolve("pope.lock"))

                // Re-resolves what's left, same as popePrune, to know exactly
                // what should still exist (including anything that was only
                // pulled in transitively by the package being removed).
                val remainingDependencies = manifest.dependencies - packageName
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages = DependencyResolver.resolveAll(remainingDependencies, registry, directSourceCacheDir)
                val expectedPaths = resolvedPackages.map { (name, resolvedPackage) -> buildPathEntryFor(name, resolvedPackage) }.toSet()

                // Every previously-locked key no longer in the remaining resolve, not just
                // packageName itself - a transitive dependency that only packageName pulled in
                // becomes stale here too, exactly like popePrune.
                val popePackagesDir = projectRoot.resolve("pope_packages")
                val staleKeys = existingLock.keys - resolvedPackages.keys
                staleKeys.forEach { staleKey ->
                    val affectedDirs = deleteInstalledPackage(popePackagesDir, staleKey, existingLock.getValue(staleKey))
                    affectedDirs.forEach { removeNowEmptyAncestors(it, popePackagesDir) }
                }
                BuildPathUpdater.pruneStalePopePackagesEntries(manifestFile, expectedPaths)
                DependenciesUpdater.removeDependency(manifestFile, packageName)

                // Reuses each remaining package's already-known integrity/installSubpath/files
                // rather than re-deriving them - none of that changed for packages that aren't
                // being reinstalled here - only falls back to fresh values if pope.lock didn't
                // already have an entry for it.
                val resolvedJson = JSONObject()
                resolvedPackages.forEach { (name, resolvedPackage) ->
                    val locked = existingLock[name]
                    val integrity = locked?.integrity ?: DirectoryHash.hash(resolvedPackage.sourceDir)
                    resolvedJson.put(
                        name,
                        JSONObject()
                            .put("version", resolvedPackage.version)
                            .put("source", resolvedPackage.sourceDir.absolutePath)
                            .put("integrity", integrity)
                            .putOpt("installSubpath", locked?.installSubpath ?: resolvedPackage.installSubpath)
                            .put("files", JSONArray(locked?.files.orEmpty())),
                    )
                }
                // Carries forward trust approvals for whichever direct-source dependencies are
                // still part of the graph - dropped entirely before, which meant any uninstall
                // silently wiped every trust approval, not just the removed package's own.
                val existingTrust = LockfileReader.readTrustedDirectSources(projectRoot.resolve("pope.lock"))
                val trustedDirectSourcesJson = JSONObject()
                existingTrust.filterKeys { it in resolvedPackages }.forEach { (key, sourceKey) -> trustedDirectSourcesJson.put(key, sourceKey) }

                val lockJson = JSONObject().put("resolved", resolvedJson).put("trustedDirectSources", trustedDirectSourcesJson)
                projectRoot.resolve("pope.lock").writeText(lockJson.toString(2))

                project.logger.lifecycle("  removed \"$packageName\"")
            }
        }

        project.tasks.register("popeInit") { task ->
            task.group = "pope"
            task.description =
                "Finishes setting up a new project once pope is already applied by coordinate: " +
                "generates or patches openedge-project.json, adds .gitignore entries, and copies the " +
                "per-project pope/pope.bat CLI. Pass -PpopePackageName=<name> if it can't be inferred " +
                "from .cls files."
            task.doLast {
                project.logger.lifecycle("=== pope init ===")
                project.logger.lifecycle("")

                val projectRoot = extension.projectRoot.get().asFile
                val explicitPackageName = project.findProperty("popePackageName") as String?

                // Bundled inside the plugin jar (see src/main/resources/pope/scaffold/) rather than
                // read off a filesystem path - unlike scaffoldProject in pope's own build.gradle.kts,
                // this task runs from a downloaded plugin with no repo checkout alongside it.
                fun scaffoldResource(name: String): String =
                    checkNotNull(javaClass.getResourceAsStream("/pope/scaffold/$name")) {
                        "Bundled resource pope/scaffold/$name is missing from the plugin jar"
                    }.bufferedReader().readText()

                val popeScript = File(projectRoot, "pope")
                popeScript.writeText(scaffoldResource("pope"))
                popeScript.setExecutable(true)
                File(projectRoot, "pope.bat").writeText(scaffoldResource("pope.bat"))

                val manifestFile = File(projectRoot, "openedge-project.json")
                if (!manifestFile.exists()) {
                    val packageName =
                        explicitPackageName ?: PackageNameInferrer.infer(File(projectRoot, "src"))
                    val rendered =
                        scaffoldResource("openedge-project.json.template")
                            .replace("{{PROJECT_NAME}}", project.name)
                            .replace("{{PACKAGE_NAME}}", packageName)
                    ManifestWriter.write(manifestFile, JSONObject(rendered))
                    project.logger.lifecycle("  + generated openedge-project.json (popePackageName: \"$packageName\")")
                } else {
                    val json = JSONObject(manifestFile.readText())
                    var patched = false

                    if (!json.has("popeDependencies")) {
                        json.put("popeDependencies", JSONObject())
                        patched = true
                    }
                    if (!json.has("popePackageName")) {
                        val sourceRoot =
                            json.optJSONArray("buildPath")?.let { entries ->
                                (0 until entries.length())
                                    .map { entries.getJSONObject(it) }
                                    .firstOrNull { it.optString("type") == "source" }
                                    ?.optString("path")
                            } ?: "src"
                        val packageName = explicitPackageName ?: PackageNameInferrer.infer(File(projectRoot, sourceRoot))
                        json.put("popePackageName", packageName)
                        project.logger.lifecycle("  + added popePackageName: \"$packageName\" to openedge-project.json")
                        patched = true
                    }

                    if (!patched) {
                        project.logger.lifecycle("  openedge-project.json already has everything pope needs - left untouched")
                    }
                    ManifestWriter.write(manifestFile, json) // written either way - also refreshes popeToolVersion
                }

                val gitignoreFile = File(projectRoot, ".gitignore")
                val gitignoreEntries = listOf("pope_packages/", ".gradle/")
                val existingGitignoreLines =
                    if (gitignoreFile.exists()) gitignoreFile.readLines().map { it.trim() }.toSet() else emptySet()
                val missingGitignoreEntries = gitignoreEntries.filter { it !in existingGitignoreLines }
                if (missingGitignoreEntries.isNotEmpty()) {
                    if (!gitignoreFile.exists()) {
                        gitignoreFile.writeText(missingGitignoreEntries.joinToString("\n", postfix = "\n"))
                        project.logger.lifecycle("  + generated .gitignore (${missingGitignoreEntries.joinToString(", ")})")
                    } else {
                        val needsLeadingNewline =
                            gitignoreFile.length() > 0 && !gitignoreFile.readText().endsWith("\n")
                        gitignoreFile.appendText(
                            (if (needsLeadingNewline) "\n" else "") + missingGitignoreEntries.joinToString("\n", postfix = "\n"),
                        )
                        project.logger.lifecycle("  + added ${missingGitignoreEntries.joinToString(", ")} to .gitignore")
                    }
                }

                project.logger.lifecycle("")
                project.logger.lifecycle("pope wiring is set up at ${projectRoot.path}")
                project.logger.lifecycle("  - add a registry: pope registry add <prefix> <url> (writes pope-registries.properties)")
                project.logger.lifecycle("  - pope install <package_name>")
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

/** The buildPath source entry for a resolved package - one per registry (shared), or one per package (isolated). */
private fun buildPathEntryFor(packageKey: String, resolvedPackage: ResolvedPackage): String =
    when (resolvedPackage.installLayout) {
        InstallLayout.SharedRegistryRoot -> "pope_packages/${resolvedPackage.installSubpath}"
        InstallLayout.Isolated -> "pope_packages/${resolvedPackage.installSubpath ?: packageKey}/src"
    }

/**
 * Installs one resolved package under popePackagesDir, returning its relative file list
 * (SharedRegistryRoot - multiple packages share one registry-named root, so only this package's own
 * files are ever touched: copied in fresh, and anything in previousFiles but not the new set - a
 * version bump that dropped/renamed a file - is deleted) or an empty list (Isolated - one dedicated
 * folder per package, always safe to wipe and recopy wholesale, same as before).
 */
private fun installPackage(
    popePackagesDir: File,
    packageKey: String,
    resolvedPackage: ResolvedPackage,
    previousFiles: List<String>,
): List<String> =
    when (resolvedPackage.installLayout) {
        InstallLayout.SharedRegistryRoot -> {
            val destinationRoot = File(popePackagesDir, requireNotNull(resolvedPackage.installSubpath))
            val newFiles = DirectoryHash.relativeFiles(resolvedPackage.sourceDir)
            newFiles.forEach { relativePath ->
                val destinationFile = File(destinationRoot, relativePath)
                destinationFile.parentFile.mkdirs()
                File(resolvedPackage.sourceDir, relativePath).copyTo(destinationFile, overwrite = true)
            }

            val staleFiles = previousFiles.toSet() - newFiles.toSet()
            val affectedParents = staleFiles.map { relativePath -> File(destinationRoot, relativePath) }.onEach { it.delete() }
            affectedParents.map { it.parentFile }.distinct().forEach { removeNowEmptyAncestors(it, popePackagesDir) }

            newFiles
        }
        InstallLayout.Isolated -> {
            val destination = File(popePackagesDir, resolvedPackage.installSubpath ?: packageKey).resolve("src")
            destination.deleteRecursively()
            resolvedPackage.sourceDir.copyRecursively(destination, overwrite = true)
            emptyList()
        }
    }

/**
 * Removes a previously-locked, now-stale package's install output, using its OLD pope.lock entry
 * (not a fresh resolve - the package is gone from the dependency graph, so there's nothing to
 * re-resolve). SharedRegistryRoot: deletes exactly its own tracked files, leaving every sibling
 * package in that same registry folder untouched. Isolated: deletes its whole dedicated folder, as
 * before. Returns the set of directories to run removeNowEmptyAncestors from.
 */
private fun deleteInstalledPackage(popePackagesDir: File, packageKey: String, locked: LockedPackage): Set<File> {
    val root = File(popePackagesDir, locked.installSubpath ?: packageKey)
    if (locked.files.isEmpty()) {
        root.deleteRecursively()
        return setOf(root.parentFile)
    }

    return locked.files
        .map { relativePath -> File(root, relativePath) }
        .onEach { it.delete() }
        .map { it.parentFile }
        .toSet()
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

/**
 * Resolves -PpopeUninstall=<spec> against the manifest's own declared dependency keys - no registry
 * search, since uninstall only ever targets something already declared. An exact key match wins
 * outright; otherwise spec is treated as a bare local name and matched against the local-name half
 * of any declared "registryName/localName" key (zero matches falls through to a typo suggestion
 * below; one auto-picks; more than one prompts the user to choose). If nothing matches even as a
 * bare local name, DidYouMean looks for a close typo among every declared key (and their local-name
 * halves) and prompts to uninstall that instead - same "did you mean X?" shape as install.
 */
private fun resolveUninstallSpec(spec: String, declaredKeys: Set<String>, userInputHandler: UserInputHandler): String {
    if (spec in declaredKeys) return spec

    val matches = declaredKeys.filter { it.substringAfterLast('/', missingDelimiterValue = "") == spec }
    when (matches.size) {
        0 -> {}
        1 -> return matches.single()
        else ->
            return userInputHandler.askUser { questions ->
                questions.choice(
                    "\"$spec\" matches more than one declared dependency - which one do you want to uninstall?",
                    matches,
                ).ask()
            }.getOrElse(matches.first())
    }

    val candidateKeysByText = declaredKeys.associateWith { it } + declaredKeys.filter { '/' in it }.associateBy { it.substringAfterLast('/') }
    val suggestion =
        DidYouMean.suggest(spec, candidateKeysByText.keys)?.let { candidateKeysByText.getValue(it) }
            ?: throw GradleException("\"$spec\" is not declared in dependencies - nothing to uninstall.")
    val question = "\"$spec\" is not declared in dependencies - did you mean \"$suggestion\"?"
    val approved = userInputHandler.askYesNoQuestion(question) ?: false
    if (!approved) throw GradleException(question)
    return suggestion
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
 * findAny(), but a "did you mean X?" suggestion becomes a yes/no prompt instead of just failing,
 * and a bare name matching no registry prefix falls back to searching every registry - by exact
 * name first, then (if nothing has it exactly) by "did you mean X?" across every registry too.
 */
private fun findAnyConfirmingTypo(
    packageName: String,
    registry: Registry,
    userInputHandler: UserInputHandler,
): Pair<String, ResolvedPackage> {
    val found =
        try {
            // findAny() returning null carries no detail - re-resolve with a versionSpec no real
            // package satisfies purely to surface the registry's own richer error instead.
            registry.findAny(packageName) ?: registry.resolve(packageName, "^0.0.0")
        } catch (e: NameNotFoundException) {
            val suggestion = e.suggestion ?: throw e
            val approved = userInputHandler.askYesNoQuestion(e.message ?: "Install the suggested name instead?") ?: false
            if (!approved) throw e
            return findAnyConfirmingTypo(suggestion, registry, userInputHandler)
        } catch (e: NoRegistryPrefixMatchException) {
            findAcrossRegistries(packageName, registry, userInputHandler)?.let { return it }
            val suggestion = (registry as? PrefixRoutingRegistry)?.suggestAcrossRegistries(packageName) ?: throw e
            // The suggestion is folded into the re-thrown message too (not just the prompt) so a
            // declined/non-interactive run's failure output still shows it, same as NameNotFoundException.
            val question = "${e.message} - did you mean \"$suggestion\"?"
            val approved = userInputHandler.askYesNoQuestion(question) ?: false
            if (!approved) throw NoRegistryPrefixMatchException(question)
            return findAnyConfirmingTypo(suggestion, registry, userInputHandler)
        }
    return packageName to found
}

/** Searches every configured registry (Registry.hasAny, no fetch) for a bare name; null if none or not a PrefixRoutingRegistry. */
private fun findAcrossRegistries(
    localName: String,
    registry: Registry,
    userInputHandler: UserInputHandler,
): Pair<String, ResolvedPackage>? {
    if (registry !is PrefixRoutingRegistry) return null

    val matches = registry.findAllMatches(localName)
    val (entry, resolvedName) =
        when (matches.size) {
            0 -> return null
            1 -> matches.single()
            else -> {
                val names = matches.map { it.first.name }
                val chosen =
                    userInputHandler.askUser { questions ->
                        questions.choice(
                            "\"$localName\" is available from more than one registry - which one do you want to install it from?",
                            names,
                        ).ask()
                    }.getOrElse(names.first())
                matches.first { (entry, _) -> entry.name == chosen }
            }
        }

    val found = entry.registry.findAny(entry.prefix + localName) ?: return null
    return resolvedName to found
}
