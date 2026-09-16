package pope.resolver

import pope.manifest.DependencySpec
import pope.registry.LocalDirectoryRegistry
import pope.registry.Registry
import pope.registry.ResolvedPackage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** version-range convenience for building a root/dependency map in tests. */
private fun versionMap(vararg pairs: Pair<String, String>): Map<String, DependencySpec> =
    pairs.associate { (name, spec) -> name to DependencySpec.Registry(spec) }

class DependencyResolverTest {
    private fun registryRoot(): File = createTempDirectory("pope-resolver-test").toFile()

    private fun directSourceCacheDir(): File = createTempDirectory("pope-resolver-test-direct").toFile()

    private fun addPackage(root: File, packageName: String, version: String, dependencies: Map<String, String> = emptyMap()) {
        val packageDir = File(root, packageName).apply { mkdirs() }
        val depsJson = dependencies.entries.joinToString(",") { (name, spec) -> "\"$name\": \"$spec\"" }
        File(packageDir, "openedge-project.json").writeText(
            """
            {
              "name": "$packageName-project",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": { $depsJson },
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(packageDir, "src").mkdirs()
    }

    /** A package whose own dependencies map is written raw, for direct-source JSON shapes. */
    private fun addPackageWithRawDeps(root: File, packageName: String, version: String, rawDependenciesJson: String) {
        val packageDir = File(root, packageName).apply { mkdirs() }
        File(packageDir, "openedge-project.json").writeText(
            """
            {
              "name": "$packageName-project",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": { $rawDependenciesJson },
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(packageDir, "src").mkdirs()
    }

    @Test
    fun `resolves a transitive dependency of a direct dependency`() {
        val root = registryRoot()
        addPackage(root, "example.calculator", "1.0.0", dependencies = mapOf("example.greeter" to "^1.0.0"))
        addPackage(root, "example.greeter", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val resolved =
            DependencyResolver.resolveAll(versionMap("example.calculator" to "^1.0.0"), registry, directSourceCacheDir())

        assertEquals(setOf("example.calculator", "example.greeter"), resolved.keys)
        assertEquals("1.0.0", resolved.getValue("example.greeter").version)
    }

    @Test
    fun `a diamond dependency with a compatible version resolves once`() {
        val root = registryRoot()
        addPackage(root, "example.a", "1.0.0", dependencies = mapOf("example.shared" to "^1.0.0"))
        addPackage(root, "example.b", "1.0.0", dependencies = mapOf("example.shared" to "^1.0.0"))
        addPackage(root, "example.shared", "1.2.0")
        val registry = LocalDirectoryRegistry(root)

        val resolved =
            DependencyResolver.resolveAll(
                versionMap("example.a" to "^1.0.0", "example.b" to "^1.0.0"),
                registry,
                directSourceCacheDir(),
            )

        assertEquals(setOf("example.a", "example.b", "example.shared"), resolved.keys)
        assertEquals("1.2.0", resolved.getValue("example.shared").version)
    }

    @Test
    fun `a diamond dependency with an incompatible version requirement fails loudly`() {
        val root = registryRoot()
        addPackage(root, "example.a", "1.0.0", dependencies = mapOf("example.shared" to "^1.0.0"))
        addPackage(root, "example.b", "1.0.0", dependencies = mapOf("example.shared" to "^2.0.0"))
        addPackage(root, "example.shared", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val error =
            assertFailsWith<IllegalStateException> {
                DependencyResolver.resolveAll(
                    versionMap("example.a" to "^1.0.0", "example.b" to "^1.0.0"),
                    registry,
                    directSourceCacheDir(),
                )
            }
        assertTrue(error.message!!.contains("Version conflict"), "Expected a version-conflict error, got: ${error.message}")
    }

    @Test
    fun `a circular dependency fails loudly instead of infinite-looping`() {
        val root = registryRoot()
        addPackage(root, "example.a", "1.0.0", dependencies = mapOf("example.b" to "^1.0.0"))
        addPackage(root, "example.b", "1.0.0", dependencies = mapOf("example.a" to "^1.0.0"))
        val registry = LocalDirectoryRegistry(root)

        val error =
            assertFailsWith<IllegalStateException> {
                DependencyResolver.resolveAll(versionMap("example.a" to "^1.0.0"), registry, directSourceCacheDir())
            }
        assertTrue(error.message!!.contains("Circular dependency"), "Expected a circular-dependency error, got: ${error.message}")
    }

    @Test
    fun `a package with no dependencies key resolves fine on its own`() {
        val root = registryRoot()
        addPackage(root, "example.calculator", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val resolved =
            DependencyResolver.resolveAll(versionMap("example.calculator" to "^1.0.0"), registry, directSourceCacheDir())

        assertEquals(setOf("example.calculator"), resolved.keys)
    }

    // --- direct-source dependencies ---

    /** Throws if ever called - proves a direct-source dependency never touches the registry. */
    private object PoisonRegistry : Registry {
        override fun resolve(packageName: String, versionSpec: String): ResolvedPackage =
            throw AssertionError("PoisonRegistry.resolve should never be called for a direct-source dependency")

        override fun findAny(packageName: String): ResolvedPackage? =
            throw AssertionError("PoisonRegistry.findAny should never be called for a direct-source dependency")
    }

    /** A standalone package repo (its own git history), tagged at its version - used as a direct-source target. */
    private fun directSourceRepo(root: File, folderName: String, packageName: String, version: String): File {
        val dir = File(root, folderName)
        addPackage(root, folderName, version)
        // addPackage already wrote package_name = folderName; overwrite to the desired packageName.
        File(dir, "openedge-project.json").writeText(
            """
            {
              "name": "$folderName-project",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        pope.fetch.GitCli.run(dir, "init", "-b", "main")
        pope.fetch.GitCli.run(dir, "config", "user.email", "pope-test@example.com")
        pope.fetch.GitCli.run(dir, "config", "user.name", "pope test")
        pope.fetch.GitCli.run(dir, "add", "-A")
        pope.fetch.GitCli.run(dir, "commit", "-m", "initial")
        pope.fetch.GitCli.run(dir, "tag", "v$version")
        return dir
    }

    @Test
    fun `a root-level direct-source dependency resolves without touching the registry, keeping its bare name`() {
        val remotesRoot = createTempDirectory("pope-resolver-direct-test").toFile()
        val greeterRepo = directSourceRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")

        val resolved =
            DependencyResolver.resolveAll(
                mapOf("greeter" to DependencySpec.DirectSource(greeterRepo.absolutePath, "v1.0.1")),
                PoisonRegistry,
                directSourceCacheDir(),
            )

        assertEquals(setOf("greeter"), resolved.keys)
        assertEquals("1.0.1", resolved.getValue("greeter").version)
        assertEquals("_direct/greeter", resolved.getValue("greeter").installSubpath)
    }

    @Test
    fun `a transitive direct-source dependency keeps its own bare declared name, no inherited prefix`() {
        val remotesRoot = createTempDirectory("pope-resolver-direct-test").toFile()
        val greeterRepo = directSourceRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")

        val calculatorRegistryRoot = registryRoot()
        addPackageWithRawDeps(
            calculatorRegistryRoot,
            // LocalDirectoryRegistry matches by manifest package_name exactly (no prefix-stripping of its
            // own, unlike CatalogRegistry) - so the fixture's package_name has to be the full routed name.
            "ba.calculator",
            "1.0.1",
            """"greeter": { "repoUrl": "${greeterRepo.absolutePath.replace("\\", "\\\\")}", "ref": "v1.0.1" }""",
        )
        val delegate = LocalDirectoryRegistry(calculatorRegistryRoot)
        val prefixRegistry =
            pope.registry.PrefixRoutingRegistry(listOf(pope.registry.RegistryEntry("ba", "ba.", delegate)))

        val resolved =
            DependencyResolver.resolveAll(
                versionMap("ba.calculator" to "^1.0.0"),
                prefixRegistry,
                directSourceCacheDir(),
            )

        assertEquals(setOf("ba.calculator", "greeter"), resolved.keys)
        assertEquals("1.0.1", resolved.getValue("greeter").version)
    }

    @Test
    fun `the same direct-source dependency declared by two differently-routed parents is deduped, not a collision`() {
        val remotesRoot = createTempDirectory("pope-resolver-direct-test").toFile()
        val greeterRepo = directSourceRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val sharedDepJson =
            """"greeter": { "repoUrl": "${greeterRepo.absolutePath.replace("\\", "\\\\")}", "ref": "v1.0.1" }"""

        val registryRootDir = registryRoot()
        addPackageWithRawDeps(registryRootDir, "ba.calculator", "1.0.1", sharedDepJson)
        addPackageWithRawDeps(registryRootDir, "cw.logger", "1.0.0", sharedDepJson)
        val delegate = LocalDirectoryRegistry(registryRootDir)
        val prefixRegistry =
            pope.registry.PrefixRoutingRegistry(
                listOf(pope.registry.RegistryEntry("ba", "ba.", delegate), pope.registry.RegistryEntry("cw", "cw.", delegate)),
            )

        val resolved =
            DependencyResolver.resolveAll(
                versionMap("ba.calculator" to "^1.0.0", "cw.logger" to "^1.0.0"),
                prefixRegistry,
                directSourceCacheDir(),
            )

        // Both parents' "greeter" dependency lands on the same bare key, so it's resolved once,
        // not flagged as a namespace collision between two differently-keyed copies.
        assertEquals(setOf("ba.calculator", "cw.logger", "greeter"), resolved.keys)
    }

    @Test
    fun `conflicting direct-source specs at the same key fail loudly`() {
        val remotesRoot = createTempDirectory("pope-resolver-direct-test").toFile()
        val greeterRepoA = directSourceRepo(remotesRoot, "greeter-repo-a", "greeter", "1.0.0")
        val greeterRepoB = directSourceRepo(remotesRoot, "greeter-repo-b", "greeter", "1.0.0")

        val calculatorRegistryRoot = registryRoot()
        addPackageWithRawDeps(
            calculatorRegistryRoot,
            "example.a",
            "1.0.0",
            """"greeter": { "repoUrl": "${greeterRepoA.absolutePath.replace("\\", "\\\\")}", "ref": "v1.0.0" }""",
        )
        addPackageWithRawDeps(
            calculatorRegistryRoot,
            "example.b",
            "1.0.0",
            """"greeter": { "repoUrl": "${greeterRepoB.absolutePath.replace("\\", "\\\\")}", "ref": "v1.0.0" }""",
        )
        val registry = LocalDirectoryRegistry(calculatorRegistryRoot)

        val error =
            assertFailsWith<IllegalStateException> {
                DependencyResolver.resolveAll(
                    versionMap("example.a" to "^1.0.0", "example.b" to "^1.0.0"),
                    registry,
                    directSourceCacheDir(),
                )
            }
        assertTrue(
            error.message!!.contains("Conflicting direct-source dependency"),
            "Expected a direct-source conflict error, got: ${error.message}",
        )
    }

    @Test
    fun `the same key resolved once as a registry dependency and once as direct-source fails loudly`() {
        val remotesRoot = createTempDirectory("pope-resolver-direct-test").toFile()
        val greeterRepo = directSourceRepo(remotesRoot, "greeter-repo", "greeter", "1.0.0")

        val root = registryRoot()
        addPackageWithRawDeps(
            root,
            "example.a",
            "1.0.0",
            """"greeter": { "repoUrl": "${greeterRepo.absolutePath.replace("\\", "\\\\")}", "ref": "v1.0.0" }""",
        )
        addPackage(root, "greeter", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val error =
            assertFailsWith<IllegalStateException> {
                DependencyResolver.resolveAll(
                    mapOf(
                        "example.a" to DependencySpec.Registry("^1.0.0"),
                        "greeter" to DependencySpec.Registry("^1.0.0"),
                    ),
                    registry,
                    directSourceCacheDir(),
                )
            }
        assertTrue(
            error.message!!.contains("Conflicting dependency kinds"),
            "Expected a conflicting-kinds error, got: ${error.message}",
        )
    }

    // --- PROPATH namespace collisions ---

    @Test
    fun `two resolved packages sharing the same real package_name fail loudly, even under different keys`() {
        val remotesRoot = createTempDirectory("pope-resolver-direct-test").toFile()
        val kgRepo = directSourceRepo(remotesRoot, "calculator-kg-repo", "calculator", "1.0.0")
        val lbsRepo = directSourceRepo(remotesRoot, "calculator-lbs-repo", "calculator", "1.0.0")

        val error =
            assertFailsWith<IllegalStateException> {
                DependencyResolver.resolveAll(
                    mapOf(
                        "calculatorKg" to DependencySpec.DirectSource(kgRepo.absolutePath, "v1.0.0"),
                        "calculatorLbs" to DependencySpec.DirectSource(lbsRepo.absolutePath, "v1.0.0"),
                    ),
                    PoisonRegistry,
                    directSourceCacheDir(),
                )
            }
        assertTrue(error.message!!.contains("PROPATH namespace collision"))
        assertTrue(error.message!!.contains("calculatorKg"))
        assertTrue(error.message!!.contains("calculatorLbs"))
        assertTrue(error.message!!.contains("\"calculator\""))
    }

    @Test
    fun `packages with different real package_names resolve fine together`() {
        val remotesRoot = createTempDirectory("pope-resolver-direct-test").toFile()
        val calculatorRepo = directSourceRepo(remotesRoot, "calculator-repo", "calculator", "1.0.0")
        val greeterRepo = directSourceRepo(remotesRoot, "greeter-repo", "greeter", "1.0.0")

        val resolved =
            DependencyResolver.resolveAll(
                mapOf(
                    "calculator" to DependencySpec.DirectSource(calculatorRepo.absolutePath, "v1.0.0"),
                    "greeter" to DependencySpec.DirectSource(greeterRepo.absolutePath, "v1.0.0"),
                ),
                PoisonRegistry,
                directSourceCacheDir(),
            )

        assertEquals(setOf("calculator", "greeter"), resolved.keys)
    }
}
