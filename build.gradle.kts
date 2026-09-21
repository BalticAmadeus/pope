// Explicit imports, not fully-qualified inline references: "java" as a
// bare identifier in this script resolves to java-gradle-plugin's own
// java {} extension accessor, not the java.* package - shadows
// java.util.Properties unless imported properly.
import java.util.Properties

// Lets the scaffoldProject task below use org.json.JSONObject directly in
// its own script body - dependencies{} further down only puts it on this
// project's compiled *output* classpath (what src/main/kotlin compiles
// against), not on the build script's own classpath.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.json:json:20250517")
    }
}

plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "1.9.24"
}

group = "io.github.balticamadeus"
version = "0.1.0-SNAPSHOT"

// java-gradle-plugin + maven-publish together auto-register a
// "pluginMaven" publication (the plugin's own jar/pom) plus a marker
// publication per entry in gradlePlugin{} below (what lets a consumer
// resolve by plugin id instead of group:artifact coordinates) - no
// publications{} block needed here.
//
// Where popePublishRepoUrl actually points is deliberately not decided
// here - default is a local, disposable folder so `./gradlew publish`
// works out of the box for testing. Real target: a git-repo-hosted Maven
// repo (see ADR-0008), once actually set up.
publishing {
    repositories {
        maven {
            name = "pope"
            url =
                uri(
                    (findProperty("popePublishRepoUrl") as String?)
                        ?: layout.buildDirectory.dir("local-maven-repo").get().asFile.toURI().toString(),
                )
        }
    }
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

val functionalTest: SourceSet by sourceSets.creating

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])

gradlePlugin {
    plugins {
        create("pope") {
            id = "io.github.balticamadeus.pope"
            implementationClass = "pope.PopePlugin"
            displayName = "pope"
            description = "Dependency management, versioning, and PROPATH generation for Progress OpenEdge ABL."
        }
    }
    testSourceSets.add(functionalTest)
}

dependencies {
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
    "functionalTestImplementation"(kotlin("test"))
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"("org.json:json:20250517")
}

tasks.test {
    useJUnitPlatform()
}

val functionalTestTask =
    tasks.register<Test>("functionalTest") {
        description = "Runs the plugin against real Gradle builds via TestKit."
        group = "verification"
        testClassesDirs = functionalTest.output.classesDirs
        classpath = functionalTest.runtimeClasspath
        useJUnitPlatform()
        // Fixtures reference demo/ files relative to the repo root.
        workingDir = rootDir
        // PublishedPluginFunctionalTest applies the plugin via a real
        // Maven repository lookup (no withPluginClasspath()/includeBuild
        // shortcut) - it needs the current version actually published
        // somewhere Gradle's normal plugin resolution can find it first.
        // mavenLocal() is used rather than popePublishRepoUrl's target
        // here since it needs no configuration and is always available.
        dependsOn("publishToMavenLocal")
        systemProperty("popePluginVersion", version.toString())
    }

tasks.check {
    dependsOn(functionalTestTask)
}

// Adds pope wiring to -PtargetDir, which may already be a real, existing
// OE project (not just an empty new one) - deliberately non-destructive:
// files that don't yet exist are generated, files that already exist are
// either left untouched (Gradle config - safely patching an arbitrary
// existing build.gradle.kts isn't attempted) or patched to add only the
// missing pope-specific fields (openedge-project.json, since that's
// tractable - it's just JSON). Static tool files (Gradle wrapper,
// pope/pope.bat) are always refreshed to match this clone's version.
//
// Deliberately a plain task in *this* build, not something PopePlugin
// registers on a consumer - a not-yet-wired project has no Gradle build
// of its own to run a task against yet, so this has to be invoked against
// pope's own (already-existing) build instead. See pope-init/
// pope-init.bat for the interactive wrapper meant for actual use;
// direct usage:
//   ./gradlew scaffoldProject -PtargetDir=. [-ProotProjectName=...] [-PpackageName=...] \
//       [-Pregistries=<prefix1>=<url1>[,<prefix2>=<url2>,...]]
tasks.register("scaffoldProject") {
    group = "pope"
    description = "Adds pope wiring to -PtargetDir (new or existing project). " +
        "Usage: ./gradlew scaffoldProject -PtargetDir=<path> [-ProotProjectName=<name>] " +
        "[-PpackageName=<name>] [-Pregistries=<prefix1>=<url1>[,<prefix2>=<url2>,...]]"

    doLast {
        logger.lifecycle("=== pope init ===")
        logger.lifecycle("")

        val targetDirProp =
            project.findProperty("targetDir") as String?
                ?: throw GradleException(
                    "Missing -PtargetDir=<path>. Usage: ./gradlew scaffoldProject -PtargetDir=<path> " +
                        "[-ProotProjectName=<name>] [-PpackageName=<name>] " +
                        "[-Pregistries=<prefix1>=<url1>[,<prefix2>=<url2>,...]]",
                )
        val targetDir = File(targetDirProp).absoluteFile
        targetDir.mkdirs()

        val explicitPackageName = project.findProperty("packageName") as String?
        val rootProjectName = project.findProperty("rootProjectName") as String? ?: targetDir.name

        val registries: List<Pair<String, String>> =
            (project.findProperty("registries") as String?)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.map { entry ->
                    val separatorIndex = entry.indexOf('=')
                    require(separatorIndex > 0) { "Malformed -Pregistries entry (expected prefix=url): \"$entry\"" }
                    entry.substring(0, separatorIndex) to entry.substring(separatorIndex + 1)
                }
                ?: emptyList()

        // Gradle's own build wiring (wrapper, settings/build/gradle.properties)
        // goes into a .pope/ subfolder for a genuinely fresh project, so the
        // project root only shows genuinely project-relevant files
        // (openedge-project.json, pope-registries.properties, pope/pope.bat,
        // src/, pope.lock, pope_packages/ once resolved). An already-legacy
        // project (root-level Gradle files already present - including the
        // real openedge-package-manager demo repo, which predates this) is
        // left exactly as-is: re-running this against it keeps writing to
        // the root, never creates a second, conflicting .pope/ copy.
        val legacyLayout =
            File(targetDir, "settings.gradle.kts").exists() ||
                File(targetDir, "build.gradle.kts").exists() ||
                File(targetDir, "gradle.properties").exists()
        val gradleFilesDir = if (legacyLayout) targetDir else File(targetDir, ".pope").apply { mkdirs() }

        // Static files always refreshed to this clone's version - nothing
        // project-specific to preserve in them. pope/pope.bat always stay
        // at targetDir root regardless of layout - they're what a user
        // actually types, hiding them away would only add friction. They
        // auto-detect .pope/ vs a root-level gradlew themselves.
        rootDir.resolve("gradlew").copyTo(File(gradleFilesDir, "gradlew"), overwrite = true)
        File(gradleFilesDir, "gradlew").setExecutable(true)
        rootDir.resolve("gradlew.bat").copyTo(File(gradleFilesDir, "gradlew.bat"), overwrite = true)
        rootDir.resolve("gradle").copyRecursively(File(gradleFilesDir, "gradle"), overwrite = true)
        rootDir.resolve("pope").copyTo(File(targetDir, "pope"), overwrite = true)
        File(targetDir, "pope").setExecutable(true)
        rootDir.resolve("pope.bat").copyTo(File(targetDir, "pope.bat"), overwrite = true)

        // Real relative path from gradleFilesDir back to this pope
        // clone - computed from where the two actually are, not a guessed
        // default.
        val popeToolPath =
            gradleFilesDir.toPath().relativize(rootDir.toPath()).toString().replace('\\', '/')

        fun renderTemplate(templateName: String, replacements: Map<String, String>): String {
            var text = rootDir.resolve("scaffold/templates/$templateName").readText()
            replacements.forEach { (token, value) -> text = text.replace("{{$token}}", value) }
            return text
        }

        // Gradle config: only written if genuinely missing - never
        // overwrite/patch an existing settings.gradle.kts/build.gradle.kts/
        // gradle.properties (arbitrary existing content, not safe to
        // pattern-match).
        val settingsFile = File(gradleFilesDir, "settings.gradle.kts")
        if (!settingsFile.exists()) {
            settingsFile.writeText(
                renderTemplate(
                    "settings.gradle.kts.template",
                    mapOf("POPE_TOOL_PATH" to popeToolPath, "ROOT_PROJECT_NAME" to rootProjectName),
                ),
            )
        } else {
            logger.warn("  ${settingsFile.path} already exists - left untouched. Needs: includeBuild(\"$popeToolPath\")")
        }

        // gradle.properties holds popeToolPath - machine-specific (wherever *this*
        // developer cloned pope, relative to this project), so it's only ever
        // written fresh, never committed for someone else to inherit a
        // possibly-wrong value - see the .gitignore entry below.
        val propertiesFile = File(gradleFilesDir, "gradle.properties")
        val gradlePropertiesFreshlyGenerated = !propertiesFile.exists()
        if (gradlePropertiesFreshlyGenerated) {
            propertiesFile.writeText(renderTemplate("gradle.properties.template", mapOf("POPE_TOOL_PATH" to popeToolPath)))
        } else {
            logger.warn("  ${propertiesFile.path} already exists - left untouched. Needs: popeToolPath=$popeToolPath")
        }

        val buildFile = File(gradleFilesDir, "build.gradle.kts")
        if (!buildFile.exists()) {
            // Only the new .pope/ layout needs projectRoot pointed back up
            // at targetDir - the legacy/root layout has Gradle's own
            // project directory already equal to the ABL project root, so
            // the default (unset) behavior is correct there.
            val projectRootBlock = if (legacyLayout) "" else "\n    projectRoot.set(file(\"..\"))"
            buildFile.writeText(renderTemplate("build.gradle.kts.template", mapOf("PROJECT_ROOT_BLOCK" to projectRootBlock)))
        } else {
            logger.warn("  ${buildFile.path} already exists - left untouched. Needs id(\"io.github.balticamadeus.pope\") applied.")
        }

        // Registries live in pope-registries.properties, independent of
        // build.gradle.kts's state - so these get applied even when
        // build.gradle.kts already existed and was left untouched above.
        // Non-destructive: only appends entries that aren't already there
        // (same name+prefix -> already done, skip silently; same name or
        // prefix but different content -> a real conflict, warn and skip
        // just that one entry rather than aborting the whole task).
        if (registries.isNotEmpty()) {
            val registriesFile = File(targetDir, "pope-registries.properties")
            val existing =
                if (registriesFile.exists()) {
                    val props = Properties()
                    registriesFile.inputStream().use { props.load(it) }
                    props.stringPropertyNames()
                        .mapNotNull { key -> key.removeSuffix(".prefix").takeIf { key.endsWith(".prefix") } }
                        .associateWith { name -> props.getProperty("$name.prefix") to props.getProperty("$name.catalogUrl") }
                } else {
                    emptyMap()
                }

            val toAppend = StringBuilder()
            for ((rawPrefix, url) in registries) {
                // A trailing "." is what lets a prefix cleanly strip off a local name (see
                // CatalogRegistry) - added automatically so a user doesn't have to remember to
                // type it themselves.
                val prefix = if (rawPrefix.endsWith(".")) rawPrefix else "$rawPrefix."
                val name = prefix.trimEnd('.')
                val current = existing[name]
                when {
                    current == (prefix to url) -> {} // already there, nothing to do
                    current != null ->
                        logger.warn(
                            "  pope-registries.properties already has \"$name\" with different values - left untouched",
                        )
                    existing.values.any { it.first == prefix } ->
                        logger.warn("  pope-registries.properties already has prefix \"$prefix\" under a different name")
                    else -> toAppend.append("$name.prefix=$prefix\n$name.catalogUrl=$url\n")
                }
            }
            if (toAppend.isNotEmpty()) {
                val needsLeadingNewline =
                    registriesFile.exists() && registriesFile.length() > 0 && !registriesFile.readText().endsWith("\n")
                registriesFile.appendText((if (needsLeadingNewline) "\n" else "") + toAppend.toString())
            }
        }

        // openedge-project.json: generate fresh if missing, else patch in
        // only the pope-specific fields that are missing - real existing
        // content (buildPath, oeversion, etc.) untouched. package_name
        // resolution (explicit -> infer from .cls files -> ask the caller
        // to prompt) is the same either way.
        val manifestFile = File(targetDir, "openedge-project.json")
        if (!manifestFile.exists()) {
            val packageName = resolvePackageName(explicitPackageName, File(targetDir, "src"))
            manifestFile.writeText(
                renderTemplate(
                    "openedge-project.json.template",
                    mapOf("PROJECT_NAME" to rootProjectName, "PACKAGE_NAME" to packageName),
                ),
            )
            logger.lifecycle("  + generated openedge-project.json (package_name: \"$packageName\")")
        } else {
            val json = org.json.JSONObject(manifestFile.readText())
            var patched = false

            if (!json.has("pope_dependencies")) {
                json.put("pope_dependencies", org.json.JSONObject())
                patched = true
            }

            if (!json.has("pope_package_name")) {
                val sourceRoot =
                    json.optJSONArray("buildPath")?.let { entries ->
                        (0 until entries.length())
                            .map { entries.getJSONObject(it) }
                            .firstOrNull { it.optString("type") == "source" }
                            ?.optString("path")
                    } ?: "src"
                val packageName = resolvePackageName(explicitPackageName, File(targetDir, sourceRoot))
                json.put("pope_package_name", packageName)
                logger.lifecycle("  + added package_name: \"$packageName\" to openedge-project.json")
                patched = true
            }

            if (patched) {
                manifestFile.writeText(json.toString(2))
            } else {
                logger.lifecycle("  openedge-project.json already has everything pope needs - left untouched")
            }
        }

        // .gitignore: pope_packages/ (full copies of every resolved dependency's
        // source, entirely regenerable from pope.lock + registries - same role
        // as node_modules/) and Gradle's own local build/cache dir don't belong
        // in source control. Non-destructive: only appends whichever entry isn't
        // already present anywhere in an existing file, never overwrites it.
        val gradleCacheEntry = if (legacyLayout) ".gradle/" else ".pope/.gradle/"
        val gradlePropertiesEntry = if (legacyLayout) "gradle.properties" else ".pope/gradle.properties"
        val gitignoreEntries =
            listOfNotNull(
                "pope_packages/",
                gradleCacheEntry,
                // Only ignore it when pope generated it fresh this run - an
                // already-existing (and presumably intentionally committed)
                // gradle.properties from before this fix is left as the
                // project's own call, not silently reinterpreted here.
                gradlePropertiesEntry.takeIf { gradlePropertiesFreshlyGenerated },
            )
        val gitignoreFile = File(targetDir, ".gitignore")
        val existingGitignoreLines =
            if (gitignoreFile.exists()) gitignoreFile.readLines().map { it.trim() }.toSet() else emptySet()
        val missingGitignoreEntries = gitignoreEntries.filter { it !in existingGitignoreLines }
        if (missingGitignoreEntries.isNotEmpty()) {
            if (!gitignoreFile.exists()) {
                gitignoreFile.writeText(missingGitignoreEntries.joinToString("\n", postfix = "\n"))
                logger.lifecycle("  + generated .gitignore (${missingGitignoreEntries.joinToString(", ")})")
            } else {
                val needsLeadingNewline =
                    gitignoreFile.length() > 0 && !gitignoreFile.readText().endsWith("\n")
                gitignoreFile.appendText(
                    (if (needsLeadingNewline) "\n" else "") + missingGitignoreEntries.joinToString("\n", postfix = "\n"),
                )
                logger.lifecycle("  + added ${missingGitignoreEntries.joinToString(", ")} to .gitignore")
            }
        }

        logger.lifecycle("")
        logger.lifecycle("pope wiring is set up at ${targetDir.path}")
        if (registries.isEmpty()) {
            logger.lifecycle("  - add a registry: pope registry add <prefix> <url> (writes pope-registries.properties)")
        }
        logger.lifecycle("  - cd ${targetDir.path} && pope.bat install <package_name>")
    }
}

/**
 * Resolves package_name for either a fresh or a patched manifest: an
 * explicit value wins outright; otherwise, best-effort namespace
 * inference from .cls files under sourceRoot (mirroring
 * pope.manifest.PackageNameInferrer's regex - duplicated, not imported:
 * build.gradle.kts's own script compilation happens before this project's
 * main sourceSet - src/main/kotlin/pope/... - is available to import
 * from). Throws a distinctly-markered error the pope-init wrapper script
 * looks for, so it can fall back to prompting interactively rather than
 * just failing outright.
 */
fun resolvePackageName(explicit: String?, sourceRoot: File): String {
    if (explicit != null) return explicit

    val classDeclaration = Regex("""(?im)^\s*class\s+([A-Za-z_][\w.]*)\s*:""")
    val namespaces =
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("cls", ignoreCase = true) }
            .mapNotNull { file -> classDeclaration.find(file.readText())?.groupValues?.get(1) }
            .map { it.substringBeforeLast('.') }
            .toSet()

    return when (namespaces.size) {
        1 -> namespaces.single()
        else -> throw GradleException(
            "PACKAGE_NAME_REQUIRED: could not infer package_name from .cls files under ${sourceRoot.path} " +
                "(found: $namespaces)",
        )
    }
}
