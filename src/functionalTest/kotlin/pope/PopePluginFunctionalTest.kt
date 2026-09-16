package pope

import org.gradle.testkit.runner.GradleRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the real plugin, via TestKit, against a throwaway copy of this
 * repo's own small fixture packages (src/functionalTest/resources/fixtures/) —
 * self-contained, not dependent on any other repo existing (the demo app
 * and registry content now live in separate repos, e.g.
 * github.com/erudys27/openedge-package-manager).
 */
class PopePluginFunctionalTest {
    /**
     * Builds a throwaway registry containing copies of this repo's own
     * calculator-package and greeter-package fixtures. Both are needed
     * together now: calculator-package genuinely depends on
     * greeter-package (see its openedge-project.json), so resolving
     * example.calculator also requires example.greeter to be findable.
     */
    private fun buildRegistry(): File {
        val registryDir = createTempDirectory("pope-functional-test-registry").toFile()

        for ((fixtureDirName, registryName) in listOf(
            "calculator-package" to "example.calculator",
            "greeter-package" to "example.greeter",
        )) {
            val fixtureDir = File("src/functionalTest/resources/fixtures/$fixtureDirName")
            require(fixtureDir.exists()) { "Fixture not found — functionalTest must run from the repo root" }
            fixtureDir.copyRecursively(File(registryDir, registryName))
        }

        return registryDir
    }

    /** Builds a throwaway consumer project applying the plugin, pointed at the given registry. */
    private fun buildProject(registryDir: File, manifestJson: JSONObject): File {
        val projectDir = createTempDirectory("pope-functional-test").toFile()
        val registryPath = registryDir.absolutePath.replace("\\", "/")

        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "consumer-app-fixture"""",
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }

            pope {
                registryRoot.set(file("$registryPath"))
            }
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(manifestJson.toString(2))
        return projectDir
    }

    private fun run(projectDir: File, vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)
            .build()

    private fun buildPathOf(projectDir: File): List<String> {
        val array = JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONArray("buildPath")
        return (0 until array.length()).map { array.getJSONObject(it).getString("path") }
    }

    @Test
    fun `popeInstall and popePropath run for real against the fixture packages`() {
        val registryDir = buildRegistry()

        // Declares only example.calculator directly - calculator-package
        // itself declares example.greeter, so a correct run resolves both
        // transitively (see DependencyResolver).
        val fixtureManifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, fixtureManifest)

        val installResult = run(projectDir, "popeInstall")

        // consumer-app declares only example.calculator directly, but
        // calculator-package itself declares example.greeter — so a
        // correct run resolves both (see DependencyResolver).
        assertTrue(
            installResult.output.contains("resolved 2 dependencies"),
            "Expected popeInstall to report both the direct and transitive dependency, got:\n${installResult.output}",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.calculator/src/example/calculator/Calculator.cls").exists(),
            "Expected the resolved package's source to be copied into pope_packages",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.greeter/src/example/greeter/Greeter.cls").exists(),
            "Expected the transitive dependency's source to be copied into pope_packages too",
        )
        assertTrue(File(projectDir, "pope.lock").exists(), "Expected popeInstall to write pope.lock")
        assertTrue(
            buildPathOf(projectDir).containsAll(
                listOf("pope_packages/example.calculator/src", "pope_packages/example.greeter/src"),
            ),
            "Expected popeInstall to auto-add both the direct and transitive dependency to buildPath, got: ${buildPathOf(projectDir)}",
        )

        val propathResult = run(projectDir, "popePropath")
        val expectedCalculatorEntry = listOf("pope_packages", "example.calculator", "src").joinToString(File.separator)
        val expectedGreeterEntry = listOf("pope_packages", "example.greeter", "src").joinToString(File.separator)
        assertTrue(
            propathResult.output.contains(expectedCalculatorEntry) && propathResult.output.contains(expectedGreeterEntry),
            "Expected popePropath output to include both resolved dependencies' source paths, got:\n${propathResult.output}",
        )
    }

    @Test
    fun `a transitive dependency's version conflict with a direct dependency fails the install loudly`() {
        val registryDir = buildRegistry()
        // example.greeter is 1.0.0 in the registry; ask directly for
        // something incompatible with what calculator-package (which is
        // also being installed) requires (^1.0.0) — must fail, not
        // silently pick one.
        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put(
                    "dependencies",
                    JSONObject()
                        .put("example.calculator", "^1.0.0")
                        .put("example.greeter", "^2.0.0"),
                )
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, manifest)

        val installResult =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeInstall")
                .buildAndFail()

        assertTrue(
            installResult.output.contains("Version conflict") && installResult.output.contains("example.greeter"),
            "Expected a version-conflict failure naming example.greeter, got:\n${installResult.output}",
        )
    }

    @Test
    fun `popeInstall -PpopeAdd adds and resolves a dependency in one step, without a prior manifest edit`() {
        val registryDir = buildRegistry()

        // No dependencies declared at all — the whole point of -PpopeAdd is
        // that the caller never touches the manifest by hand first.
        val emptyManifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject())
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, emptyManifest)

        val installResult = run(projectDir, "popeInstall", "-PpopeAdd=example.calculator")

        assertTrue(
            installResult.output.contains("added \"example.calculator\": \"^1.0.0\" to dependencies"),
            "Expected popeInstall to report the auto-picked version, got:\n${installResult.output}",
        )

        val dependencies = JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONObject("dependencies")
        assertTrue(
            dependencies.getString("example.calculator") == "^1.0.0",
            "Expected dependencies to contain the added package, got: $dependencies",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.calculator/src/example/calculator/Calculator.cls").exists(),
            "Expected -PpopeAdd to also resolve the newly added dependency in the same run",
        )
        assertTrue(
            buildPathOf(projectDir).contains("pope_packages/example.calculator/src"),
            "Expected buildPath to be updated in the same run, got: ${buildPathOf(projectDir)}",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.greeter/src/example/greeter/Greeter.cls").exists(),
            "Expected -PpopeAdd to also resolve example.calculator's own transitive dependency on example.greeter",
        )
    }

    @Test
    fun `a failed -PpopeAdd leaves the manifest's dependencies untouched`() {
        val registryDir = buildRegistry()

        // example.calculator is already declared, and (transitively) needs
        // example.greeter ^1.0.0. Adding example.greeter directly with an
        // incompatible version conflicts — the install must fail, and
        // critically, must NOT have written example.greeter into
        // dependencies before failing.
        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, manifest)

        val installResult =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeInstall", "-PpopeAdd=example.greeter:^2.0.0")
                .buildAndFail()

        assertTrue(
            installResult.output.contains("Version conflict"),
            "Expected a version-conflict failure, got:\n${installResult.output}",
        )

        val dependencies = JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONObject("dependencies")
        assertTrue(
            !dependencies.has("example.greeter"),
            "Expected example.greeter to NOT be written to dependencies after a failed install, got: $dependencies",
        )
        assertTrue(
            !File(projectDir, "pope_packages").exists(),
            "Expected no pope_packages to be written after a failed install",
        )
        assertTrue(
            !File(projectDir, "pope.lock").exists(),
            "Expected no pope.lock to be written after a failed install",
        )
    }

    // --- registries{} DSL + pope-registries.properties merging ---

    /**
     * A project with one registry from the registries{} DSL and one from
     * pope-registries.properties - neither URL needs to be real: this
     * only exercises PrefixRoutingRegistry's own routing/merge logic
     * (surfaced via its "no configured prefix matches" error listing both
     * prefixes), never an actual fetch.
     */
    private fun buildMergedRegistriesProject(): File {
        val projectDir = createTempDirectory("pope-functional-test-merge").toFile()

        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "merge-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }

            pope {
                registries {
                    create("x") {
                        prefix.set("x.")
                        catalogUrl.set("https://example.invalid/x.git")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("pope-registries.properties").writeText(
            """
            y.prefix=y.
            y.catalogUrl=https://example.invalid/y.git
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "merge-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.merge")
                .put("dependencies", JSONObject().put("z.something", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )
        return projectDir
    }

    @Test
    fun `registries from the DSL and pope-registries properties are both applied`() {
        val projectDir = buildMergedRegistriesProject()

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeInstall")
                .buildAndFail()

        assertTrue(
            result.output.contains("x.") && result.output.contains("y."),
            "Expected both the DSL registry (x.) and the properties-file registry (y.) to be " +
                "configured, got:\n${result.output}",
        )
    }

    @Test
    fun `a prefix declared in both the DSL and pope-registries properties fails loudly`() {
        val projectDir = buildMergedRegistriesProject()
        // Redeclare the DSL's "x." prefix under a different name in the properties file too.
        projectDir.resolve("pope-registries.properties").appendText(
            "\nconflict.prefix=x.\nconflict.catalogUrl=https://example.invalid/conflict.git\n",
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeInstall")
                .buildAndFail()

        assertTrue(
            result.output.contains("Duplicate registry prefix"),
            "Expected a duplicate-prefix failure, got:\n${result.output}",
        )
    }

    @Test
    fun `a name declared in both the DSL and pope-registries properties fails loudly`() {
        val projectDir = buildMergedRegistriesProject()
        // Redeclare the DSL's "x" name under a different prefix in the properties file too.
        projectDir.resolve("pope-registries.properties").appendText(
            "\nx.prefix=w.\nx.catalogUrl=https://example.invalid/w.git\n",
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeInstall")
                .buildAndFail()

        assertTrue(
            result.output.contains("Duplicate registry name"),
            "Expected a duplicate-name failure, got:\n${result.output}",
        )
    }

    // --- projectRoot != Gradle's own project directory ---

    @Test
    fun `projectRoot lets the ABL project live one level up from Gradle's own files`() {
        val registryDir = buildRegistry()
        val registryPath = registryDir.absolutePath.replace("\\", "/")

        // Gradle's own files (settings.gradle.kts/build.gradle.kts) live in
        // a subfolder; openedge-project.json/src/etc. live at abRoot, one
        // level up - the ".pope/" layout scaffoldProject can generate.
        val abRoot = createTempDirectory("pope-functional-test-projectroot").toFile()
        val gradleFilesDir = File(abRoot, ".pope").apply { mkdirs() }

        gradleFilesDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "projectroot-fixture"""")
        gradleFilesDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }

            pope {
                projectRoot.set(file(".."))
                registryRoot.set(file("$registryPath"))
            }
            """.trimIndent(),
        )
        abRoot.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "projectroot-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )

        val installResult =
            GradleRunner.create()
                .withProjectDir(gradleFilesDir)
                .withPluginClasspath()
                .withArguments("popeInstall")
                .build()

        assertTrue(
            installResult.output.contains("resolved 2 dependencies"),
            "Expected both the direct and transitive dependency resolved, got:\n${installResult.output}",
        )
        assertTrue(
            File(abRoot, "pope_packages/example.calculator/src/example/calculator/Calculator.cls").exists(),
            "Expected pope_packages to be written at the ABL project root (abRoot), not inside .pope/",
        )
        assertTrue(!File(gradleFilesDir, "pope_packages").exists(), "Expected no pope_packages inside .pope/")
        assertTrue(File(abRoot, "pope.lock").exists(), "Expected pope.lock at abRoot")
        assertTrue(!File(gradleFilesDir, "pope.lock").exists(), "Expected no pope.lock inside .pope/")

        val propathResult =
            GradleRunner.create()
                .withProjectDir(gradleFilesDir)
                .withPluginClasspath()
                .withArguments("popePropath")
                .build()
        val expectedSrcEntry = File(abRoot, "src").absolutePath
        assertTrue(
            propathResult.output.contains(expectedSrcEntry),
            "Expected popePropath to resolve buildPath entries against abRoot, got:\n${propathResult.output}",
        )
    }

    // --- pope_packages/ nested-by-prefix layout ---

    /**
     * functionalTest doesn't compile against the plugin's main sourceSet
     * (it only exercises the plugin via GradleRunner/withPluginClasspath),
     * so pope.fetch.GitCli isn't visible here - a plain ProcessBuilder call
     * does the same job for building fixture git repos.
     */
    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "git ${args.joinToString(" ")} failed (exit $exitCode):\n$output" }
    }

    private fun gitPackageRepo(root: File, folderName: String, packageName: String, version: String): File {
        val dir = File(root, folderName)
        dir.mkdirs()
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "pope-test@example.com")
        git(dir, "config", "user.name", "pope test")
        File(dir, "openedge-project.json").writeText(
            JSONObject()
                .put("name", "$folderName-project")
                .put("version", version)
                .put("package_name", packageName)
                .put("dependencies", JSONObject())
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )
        val classDir = File(dir, "src/${packageName.replace('.', '/')}")
        classDir.mkdirs()
        val className = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        File(classDir, "$className.cls").writeText("class $packageName.$className:\nend class.")
        git(dir, "add", "-A")
        git(dir, "commit", "-m", "initial")
        git(dir, "tag", "v$version")
        return dir
    }

    @Test
    fun `pope_packages nests a catalog-routed package by its registry prefix, and a direct-source one under _direct`() {
        val remotesRoot = createTempDirectory("pope-functional-test-nested-remotes").toFile()

        val greeterRepo = gitPackageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val calculatorRepo = gitPackageRepo(remotesRoot, "calculator-repo", "calculator", "1.0.0")
        // calculator depends on greeter directly by source (no registry involved).
        File(calculatorRepo, "openedge-project.json").writeText(
            JSONObject()
                .put("name", "calculator-repo-project")
                .put("version", "1.0.0")
                .put("package_name", "calculator")
                .put(
                    "dependencies",
                    JSONObject().put(
                        "greeter",
                        JSONObject().put("repoUrl", greeterRepo.absolutePath.replace("\\", "/")).put("ref", "v1.0.1"),
                    ),
                )
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )
        git(calculatorRepo, "add", "-A")
        git(calculatorRepo, "commit", "-m", "declare direct-source dependency on greeter")
        // gitPackageRepo() already tagged v1.0.0 at the initial commit - move it to
        // this one, which is the one that actually declares the greeter dependency.
        git(calculatorRepo, "tag", "-f", "v1.0.0")

        val catalogDir = File(remotesRoot, "catalog")
        catalogDir.mkdirs()
        git(catalogDir, "init", "-b", "main")
        git(catalogDir, "config", "user.email", "pope-test@example.com")
        git(catalogDir, "config", "user.name", "pope test")
        File(catalogDir, "packages/calculator").mkdirs()
        File(catalogDir, "packages/calculator/1.0.0.json").writeText(
            JSONObject()
                .put("repoUrl", calculatorRepo.absolutePath.replace("\\", "/"))
                .put("version", "1.0.0")
                .put("ref", "v1.0.0")
                .toString(2),
        )
        git(catalogDir, "add", "-A")
        git(catalogDir, "commit", "-m", "add calculator 1.0.0")

        val projectDir = createTempDirectory("pope-functional-test-nested-project").toFile()
        // Without an explicit cacheDir, this would default to the real
        // ~/.pope/cache - fine normally, but this test's package names
        // ("ba/calculator", "greeter") can collide with genuinely
        // different content already cached there from real, live use of
        // this same machine, so a throwaway temp dir keeps this test
        // fully isolated.
        val cacheDir = createTempDirectory("pope-functional-test-nested-cache").toFile()
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "nested-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }

            pope {
                cacheDir.set(file("${cacheDir.absolutePath.replace("\\", "/")}"))
                registries {
                    create("ba") {
                        prefix.set("ba.")
                        catalogUrl.set("${catalogDir.absolutePath.replace("\\", "/")}")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "nested-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("ba.calculator", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )

        val installResult = run(projectDir, "popeInstall")

        assertTrue(
            installResult.output.contains("resolved 2 dependencies"),
            "Expected both ba.calculator and its transitive greeter dependency resolved, got:\n${installResult.output}",
        )
        assertTrue(
            File(projectDir, "pope_packages/ba/calculator/src/calculator/Calculator.cls").exists(),
            "Expected the catalog-routed package to be nested under pope_packages/ba/calculator",
        )
        assertTrue(
            File(projectDir, "pope_packages/_direct/greeter/src/greeter/Greeter.cls").exists(),
            "Expected the direct-source dependency to be nested under pope_packages/_direct/greeter",
        )
        assertTrue(
            buildPathOf(projectDir).containsAll(
                listOf("pope_packages/ba/calculator/src", "pope_packages/_direct/greeter/src"),
            ),
            "Expected buildPath to reference the nested paths, got: ${buildPathOf(projectDir)}",
        )
    }

    // --- explicit "registryName/localName" selection ---

    @Test
    fun `an explicit registryName-localName dependency resolves via that registry, nested by its real prefix`() {
        val remotesRoot = createTempDirectory("pope-functional-test-explicit-remotes").toFile()
        val calculatorRepo = gitPackageRepo(remotesRoot, "calculator-repo", "calculator", "1.0.0")

        val catalogDir = File(remotesRoot, "catalog")
        catalogDir.mkdirs()
        git(catalogDir, "init", "-b", "main")
        git(catalogDir, "config", "user.email", "pope-test@example.com")
        git(catalogDir, "config", "user.name", "pope test")
        File(catalogDir, "packages/calculator").mkdirs()
        File(catalogDir, "packages/calculator/1.0.0.json").writeText(
            JSONObject()
                .put("repoUrl", calculatorRepo.absolutePath.replace("\\", "/"))
                .put("version", "1.0.0")
                .put("ref", "v1.0.0")
                .toString(2),
        )
        git(catalogDir, "add", "-A")
        git(catalogDir, "commit", "-m", "add calculator 1.0.0")

        val projectDir = createTempDirectory("pope-functional-test-explicit-project").toFile()
        // Isolated cacheDir - see the comment on the equivalent line in the nested-layout test above.
        val cacheDir = createTempDirectory("pope-functional-test-explicit-cache").toFile()
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "explicit-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }

            pope {
                cacheDir.set(file("${cacheDir.absolutePath.replace("\\", "/")}"))
                registries {
                    // Registered as "registry-ba" with real prefix "ba." - deliberately
                    // different from the label, to exercise explicit name-based selection
                    // decoupled from prefix-based routing.
                    create("registry-ba") {
                        prefix.set("ba.")
                        catalogUrl.set("${catalogDir.absolutePath.replace("\\", "/")}")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "explicit-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("registry-ba/calculator", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )

        val installResult = run(projectDir, "popeInstall")

        assertTrue(
            installResult.output.contains("resolved 1 dependencies"),
            "Expected the explicitly-selected registry-ba/calculator resolved, got:\n${installResult.output}",
        )
        assertTrue(
            File(projectDir, "pope_packages/ba/calculator/src/calculator/Calculator.cls").exists(),
            "Expected the explicitly-selected package to land at pope_packages/ba/calculator " +
                "(its real prefix), not pope_packages/registry-ba/calculator (its dependency key)",
        )
    }

    // --- buildPath "test" type ---

    @Test
    fun `popePropath only includes buildPath test entries when -PpopeIncludeTests is passed`() {
        val registryDir = buildRegistry()
        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject())
                .put(
                    "buildPath",
                    JSONArray()
                        .put(JSONObject().put("type", "source").put("path", "src"))
                        .put(JSONObject().put("type", "test").put("path", "test")),
                )
        val projectDir = buildProject(registryDir, manifest)
        File(projectDir, "src").mkdirs()
        File(projectDir, "test").mkdirs()

        val expectedSrcEntry = File(projectDir, "src").absolutePath
        val expectedTestEntry = File(projectDir, "test").absolutePath

        val withoutFlag = run(projectDir, "popePropath")
        assertTrue(withoutFlag.output.contains(expectedSrcEntry), "Expected source root in output, got:\n${withoutFlag.output}")
        assertTrue(
            !withoutFlag.output.contains(expectedTestEntry),
            "Expected test root NOT in output without -PpopeIncludeTests, got:\n${withoutFlag.output}",
        )

        val withFlag = run(projectDir, "popePropath", "-PpopeIncludeTests")
        assertTrue(
            withFlag.output.contains(expectedSrcEntry) && withFlag.output.contains(expectedTestEntry),
            "Expected both source and test roots with -PpopeIncludeTests, got:\n${withFlag.output}",
        )
    }

    @Test
    fun `a dependency's own test entries are never copied into pope_packages or added to a consumer's buildPath`() {
        val registryDir = createTempDirectory("pope-functional-test-registry-hastests").toFile()
        val packageDir = File(registryDir, "example.hastests")
        packageDir.resolve("src/example/hastests").mkdirs()
        packageDir.resolve("src/example/hastests/Thing.cls").writeText("class example.hastests.Thing:\nend class.\n")
        packageDir.resolve("test").mkdirs()
        packageDir.resolve("test/ThingTest.cls").writeText("class ThingTest:\nend class.\n")
        packageDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "hastests-package")
                .put("version", "1.0.0")
                .put("package_name", "example.hastests")
                .put("dependencies", JSONObject())
                .put(
                    "buildPath",
                    JSONArray()
                        .put(JSONObject().put("type", "source").put("path", "src"))
                        .put(JSONObject().put("type", "test").put("path", "test")),
                )
                .toString(2),
        )

        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.hastests", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
        val projectDir = buildProject(registryDir, manifest)

        run(projectDir, "popeInstall")

        assertTrue(
            File(projectDir, "pope_packages/example.hastests/src/example/hastests/Thing.cls").exists(),
            "Expected the dependency's source to be copied into pope_packages",
        )
        assertTrue(
            !File(projectDir, "pope_packages/example.hastests/test").exists(),
            "Expected the dependency's own test folder to never be copied into pope_packages at all",
        )
        assertTrue(
            "pope_packages/example.hastests/test" !in buildPathOf(projectDir),
            "Expected no test-folder entry added to the consumer's buildPath, got: ${buildPathOf(projectDir)}",
        )
    }

    // --- popePrune ---

    private fun writeManifest(projectDir: File, dependencyNames: List<String>) {
        val manifestFile = projectDir.resolve("openedge-project.json")
        val dependencies = JSONObject()
        dependencyNames.forEach { dependencies.put(it, "^1.0.0") }

        // Only touches "dependencies" - a fresh manifest the first time
        // (file doesn't exist yet), an in-place edit after popeInstall has
        // already run once (must preserve the buildPath entries it wrote).
        val json =
            if (manifestFile.exists()) {
                JSONObject(manifestFile.readText())
            } else {
                JSONObject()
                    .put("name", "consumer-app-fixture")
                    .put("version", "1.0.0")
                    .put("package_name", "example.consumer")
                    .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
            }
        json.put("dependencies", dependencies)
        manifestFile.writeText(json.toString(2))
    }

    @Test
    fun `popePrune removes a no-longer-declared dependency's pope_packages folder and buildPath entry, leaving others alone`() {
        val registryDir = createTempDirectory("pope-functional-test-prune-registry").toFile()
        for (name in listOf("alpha", "beta")) {
            val packageDir = File(registryDir, "example.$name")
            packageDir.resolve("src/example/$name").mkdirs()
            packageDir.resolve("src/example/$name/Thing.cls").writeText("class example.$name.Thing:\nend class.\n")
            packageDir.resolve("openedge-project.json").writeText(
                JSONObject()
                    .put("name", "$name-package")
                    .put("version", "1.0.0")
                    .put("package_name", "example.$name")
                    .put("dependencies", JSONObject())
                    .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                    .toString(2),
            )
        }

        val projectDir = createTempDirectory("pope-functional-test-prune-project").toFile()
        val registryPath = registryDir.absolutePath.replace("\\", "/")
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "prune-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }

            pope {
                registryRoot.set(file("$registryPath"))
            }
            """.trimIndent(),
        )
        writeManifest(projectDir, listOf("example.alpha", "example.beta"))

        run(projectDir, "popeInstall")
        assertTrue(File(projectDir, "pope_packages/example.alpha/src").exists())
        assertTrue(File(projectDir, "pope_packages/example.beta/src").exists())

        // beta is no longer declared - a real edit, same as a user removing a dependency by hand.
        writeManifest(projectDir, listOf("example.alpha"))

        val dryRunResult = run(projectDir, "popePrune", "-PpopeDryRun")
        assertTrue(
            dryRunResult.output.contains("would remove") && dryRunResult.output.contains("example.beta"),
            "Expected a dry-run report naming example.beta, got:\n${dryRunResult.output}",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.beta/src").exists(),
            "Expected dry-run to leave pope_packages/example.beta untouched",
        )
        assertTrue(
            "pope_packages/example.beta/src" in buildPathOf(projectDir),
            "Expected dry-run to leave the buildPath entry untouched",
        )

        val pruneResult = run(projectDir, "popePrune")
        assertTrue(
            pruneResult.output.contains("removed") && pruneResult.output.contains("example.beta"),
            "Expected a real-run report naming example.beta, got:\n${pruneResult.output}",
        )
        assertTrue(
            !File(projectDir, "pope_packages/example.beta").exists(),
            "Expected example.beta's whole folder to be removed from pope_packages",
        )
        assertTrue(
            "pope_packages/example.beta/src" !in buildPathOf(projectDir),
            "Expected example.beta's buildPath entry to be removed",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.alpha/src/example/alpha/Thing.cls").exists(),
            "Expected example.alpha (still declared) to be left alone",
        )
        assertTrue(
            "pope_packages/example.alpha/src" in buildPathOf(projectDir),
            "Expected example.alpha's buildPath entry to be left alone",
        )
    }

    @Test
    fun `popePrune reports nothing to remove when everything installed is still declared`() {
        val registryDir = buildRegistry()
        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
        val projectDir = buildProject(registryDir, manifest)

        run(projectDir, "popeInstall")
        val pruneResult = run(projectDir, "popePrune")

        assertTrue(
            pruneResult.output.contains("nothing to remove"),
            "Expected nothing to remove, got:\n${pruneResult.output}",
        )
    }

    // --- popeUninstall ---

    private fun buildTwoIndependentPackagesProject(): Pair<File, File> {
        val registryDir = createTempDirectory("pope-functional-test-uninstall-registry").toFile()
        for (name in listOf("alpha", "beta")) {
            val packageDir = File(registryDir, "example.$name")
            packageDir.resolve("src/example/$name").mkdirs()
            packageDir.resolve("src/example/$name/Thing.cls").writeText("class example.$name.Thing:\nend class.\n")
            packageDir.resolve("openedge-project.json").writeText(
                JSONObject()
                    .put("name", "$name-package")
                    .put("version", "1.0.0")
                    .put("package_name", "example.$name")
                    .put("dependencies", JSONObject())
                    .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                    .toString(2),
            )
        }

        val projectDir = createTempDirectory("pope-functional-test-uninstall-project").toFile()
        val registryPath = registryDir.absolutePath.replace("\\", "/")
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "uninstall-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }

            pope {
                registryRoot.set(file("$registryPath"))
            }
            """.trimIndent(),
        )
        writeManifest(projectDir, listOf("example.alpha", "example.beta"))
        return registryDir to projectDir
    }

    @Test
    fun `popeUninstall removes a dependency and its pope_packages, buildPath, and lock entries, leaving others alone`() {
        val (_, projectDir) = buildTwoIndependentPackagesProject()

        run(projectDir, "popeInstall")
        assertTrue(File(projectDir, "pope_packages/example.beta/src").exists())

        val uninstallResult = run(projectDir, "popeUninstall", "-PpopeUninstall=example.beta")

        assertTrue(
            uninstallResult.output.contains("removed \"example.beta\""),
            "Expected a summary naming example.beta, got:\n${uninstallResult.output}",
        )
        assertTrue(
            !JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONObject("dependencies").has("example.beta"),
            "Expected example.beta removed from dependencies",
        )
        assertTrue(
            !File(projectDir, "pope_packages/example.beta").exists(),
            "Expected example.beta's whole folder removed from pope_packages",
        )
        assertTrue(
            "pope_packages/example.beta/src" !in buildPathOf(projectDir),
            "Expected example.beta's buildPath entry removed",
        )
        assertTrue(
            !JSONObject(File(projectDir, "pope.lock").readText()).getJSONObject("resolved").has("example.beta"),
            "Expected example.beta removed from pope.lock",
        )

        assertTrue(
            JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONObject("dependencies").has("example.alpha"),
            "Expected example.alpha (not uninstalled) to be left alone in dependencies",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.alpha/src/example/alpha/Thing.cls").exists(),
            "Expected example.alpha to be left alone in pope_packages",
        )
        assertTrue(
            "pope_packages/example.alpha/src" in buildPathOf(projectDir),
            "Expected example.alpha's buildPath entry to be left alone",
        )
        assertTrue(
            JSONObject(File(projectDir, "pope.lock").readText()).getJSONObject("resolved").has("example.alpha"),
            "Expected example.alpha to be left alone in pope.lock",
        )
    }

    @Test
    fun `popeUninstall fails loudly for a package that isn't declared`() {
        val (_, projectDir) = buildTwoIndependentPackagesProject()
        run(projectDir, "popeInstall")

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeUninstall", "-PpopeUninstall=example.nonexistent")
                .buildAndFail()

        assertTrue(
            result.output.contains("example.nonexistent") && result.output.contains("not declared"),
            "Expected a clear error naming example.nonexistent, got:\n${result.output}",
        )
    }
}
