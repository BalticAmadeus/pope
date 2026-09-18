package pope.registry

import pope.fetch.GitCli
import pope.suggest.NameNotFoundException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogRegistryTest {
    private fun git(dir: File, vararg args: String) = GitCli.run(dir, *args)

    private fun initRepo(dir: File) {
        dir.mkdirs()
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "pope-test@example.com")
        git(dir, "config", "user.name", "pope test")
    }

    private fun commitAll(dir: File, message: String) {
        git(dir, "add", "-A")
        git(dir, "commit", "-m", message)
    }

    /** A standalone package repo, tagged at its declared version. */
    private fun packageRepo(root: File, folderName: String, packageName: String, version: String): File {
        val dir = File(root, folderName)
        initRepo(dir)
        File(dir, "openedge-project.json").writeText(
            """
            {
              "name": "$folderName",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(dir, "src").mkdirs()
        File(dir, "src/marker.i").writeText("/* $packageName source marker */")
        commitAll(dir, "initial")
        git(dir, "tag", "v$version")
        return dir
    }

    private fun catalogRepo(root: File, references: Map<String, Pair<File, String>>): File {
        val dir = File(root, "catalog")
        initRepo(dir)
        for ((packageName, repoAndVersion) in references) {
            val (repoDir, version) = repoAndVersion
            File(dir, "packages/$packageName").mkdirs()
            File(dir, "packages/$packageName/$version.json").writeText(
                """
                { "repoUrl": "${repoDir.absolutePath.replace("\\", "\\\\")}", "version": "$version", "ref": "v$version" }
                """.trimIndent(),
            )
        }
        // An empty catalog (no references passed - tests that add their
        // own via addReference afterward) has nothing staged yet; a plain
        // "git commit" would fail with nothing to commit.
        if (references.isEmpty()) git(dir, "commit", "--allow-empty", "-m", "initial") else commitAll(dir, "initial")
        return dir
    }

    private fun registry(catalog: File, cacheDir: File, prefix: String = "") =
        CatalogRegistry("test", prefix, catalog.absolutePath, "main", cacheDir)

    @Test
    fun `resolves a package via the catalog and fetches only that package`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val greeterRepo = packageRepo(remotesRoot, "greeter-package", "example.greeter", "1.0.0")
        val catalog =
            catalogRepo(
                remotesRoot,
                mapOf("example.calculator" to (calculatorRepo to "1.0.0"), "example.greeter" to (greeterRepo to "1.0.0")),
            )

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        val resolved = registry.findAny("example.calculator")

        assertEquals("example.calculator", resolved?.packageName)
        assertEquals("1.0.0", resolved?.version)
        assertEquals("src", resolved?.sourceDir?.name)
        assertEquals("test", resolved?.installSubpath)
        assertEquals(InstallLayout.SharedRegistryRoot, resolved?.installLayout)
        assertTrue(File(cacheDir, "example.calculator/_bare.git/HEAD").exists())
        assertTrue(File(cacheDir, "example.calculator/v1.0.0/.git").exists())
        assertFalse(File(cacheDir, "example.greeter").exists())
    }

    @Test
    fun `resolve checks the version range`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        assertEquals("1.0.0", registry.resolve("example.calculator", "^1.0.0").version)
        assertFailsWith<IllegalStateException> { registry.resolve("example.calculator", "^2.0.0") }
    }

    @Test
    fun `resolve throws NameNotFoundException with a did-you-mean suggestion for a close typo`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir, prefix = "ba.")

        val error =
            assertFailsWith<NameNotFoundException> { registry.resolve("ba.claculator", "^1.0.0") }

        // "registryName/localName" - not prefix-concatenated - so it reads clean and retries
        // correctly no matter what this registry's own configured prefix looks like.
        assertEquals("test/calculator", error.suggestion)
        assertTrue(error.message!!.contains("did you mean \"calculator\""))
    }

    @Test
    fun `resolve throws NameNotFoundException with no suggestion when nothing is close`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir, prefix = "ba.")

        val error =
            assertFailsWith<NameNotFoundException> { registry.resolve("ba.zzzzzzzzzzzz", "^1.0.0") }

        assertNull(error.suggestion)
        assertFalse(error.message!!.contains("did you mean"))
    }

    @Test
    fun `findAny returns null when the catalog has no entry for that package`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        assertNull(registry.findAny("example.other"))
    }

    @Test
    fun `hasAny is true for a real entry and false for a missing one`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        assertTrue(registry.hasAny("example.calculator"))
        assertFalse(registry.hasAny("example.other"))
    }

    @Test
    fun `hasAny never fetches the real package - only findAny-resolve do`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        assertTrue(registry.hasAny("example.calculator"))

        // The catalog itself gets cloned (cheap - no package content), but the real package's own
        // repo must never be touched by hasAny - only by findAny/resolve, once something's chosen.
        assertTrue(File(cacheDir, "_catalog/.git").exists(), "Expected the small catalog repo to be cloned")
        assertFalse(
            File(cacheDir, "example.calculator").exists(),
            "Expected the real package's own cache folder to NOT exist - hasAny must never fetch it",
        )
    }

    @Test
    fun `suggestAny finds the closest local name for a typo, without fetching, and null when nothing is close`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        assertEquals("example.calculator", registry.suggestAny("example.claculator"))
        assertNull(registry.suggestAny("example.zzzzzzzzzzzz"))
        assertFalse(
            File(cacheDir, "example.calculator").exists(),
            "Expected suggestAny to never fetch the real package either",
        )
    }

    @Test
    fun `a second resolve reuses the cached catalog and package clones without error`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        registry.findAny("example.calculator")
        val second = registry.findAny("example.calculator")

        assertEquals("1.0.0", second?.version)
    }

    @Test
    fun `a package's worktree is checked out at exactly the resolved ref, even though the bare cache holds full history`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        // A commit after the tag, so the worktree (checked out at the tag)
        // must not see it even though the bare repo's history does.
        File(calculatorRepo, "src/extra.i").writeText("/* extra */")
        commitAll(calculatorRepo, "second commit")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        registry.findAny("example.calculator")

        val worktreeDir = File(cacheDir, "example.calculator/v1.0.0")
        val commitCount = git(worktreeDir, "rev-list", "--count", "HEAD").trim()
        assertEquals("1", commitCount)

        val bareRepoDir = File(cacheDir, "example.calculator/_bare.git")
        val bareCommitCount = git(bareRepoDir, "rev-list", "--count", "--all").trim()
        assertEquals("2", bareCommitCount)
    }

    @Test
    fun `a second, different version reuses the bare cache and only fetches locally`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepoV1 = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        File(calculatorRepoV1, "openedge-project.json").writeText(
            """
            {
              "name": "calculator-package-project",
              "version": "2.0.0",
              "package_name": "example.calculator",
              "dependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(calculatorRepoV1, "src/marker.i").writeText("/* example.calculator source marker v2 */")
        commitAll(calculatorRepoV1, "bump to 2.0.0")
        git(calculatorRepoV1, "tag", "v2.0.0")

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepoV1 to "1.0.0")))

        registry(catalog, cacheDir).findAny("example.calculator")
        assertTrue(File(cacheDir, "example.calculator/v1.0.0/.git").exists())

        // Re-point the same catalog repo at 2.0.0 and resolve again - same
        // bare package repo already has both tags, so this is a local
        // worktree add, not a re-clone; a second worktree lands alongside
        // the first rather than replacing it.
        File(catalog, "packages/example.calculator/1.0.0.json").delete()
        File(catalog, "packages/example.calculator/2.0.0.json").writeText(
            """
            { "repoUrl": "${calculatorRepoV1.absolutePath.replace("\\", "\\\\")}", "version": "2.0.0", "ref": "v2.0.0" }
            """.trimIndent(),
        )
        commitAll(catalog, "bump to 2.0.0")

        val resolved = registry(catalog, cacheDir).findAny("example.calculator")

        assertEquals("2.0.0", resolved?.version)
        assertTrue(File(cacheDir, "example.calculator/v2.0.0/.git").exists())
        assertTrue(File(cacheDir, "example.calculator/v1.0.0/.git").exists())
    }

    /** One repo, tagged at each of the given versions in sequence (v1.0.0 first, then v1.5.0, etc.). */
    private fun packageRepoWithVersions(root: File, folderName: String, packageName: String, versions: List<String>): File {
        val dir = File(root, folderName)
        initRepo(dir)
        for (version in versions) {
            File(dir, "openedge-project.json").writeText(
                """
                {
                  "name": "$folderName",
                  "version": "$version",
                  "package_name": "$packageName",
                  "dependencies": {},
                  "buildPath": [{ "type": "source", "path": "src" }]
                }
                """.trimIndent(),
            )
            File(dir, "src").mkdirs()
            File(dir, "src/marker.i").writeText("/* $packageName source marker v$version */")
            commitAll(dir, "version $version")
            git(dir, "tag", "v$version")
        }
        return dir
    }

    /** Writes (or overwrites) one catalog reference file, without touching any others already there. */
    private fun addReference(catalog: File, localName: String, version: String, repoDir: File, ref: String = "v$version") {
        File(catalog, "packages/$localName").mkdirs()
        File(catalog, "packages/$localName/$version.json").writeText(
            """
            { "repoUrl": "${repoDir.absolutePath.replace("\\", "\\\\")}", "version": "$version", "ref": "$ref" }
            """.trimIndent(),
        )
        git(catalog, "add", "-A")
        git(catalog, "commit", "-m", "add version $version")
    }

    @Test
    fun `findAny picks the highest available version when a package's catalog folder has more than one`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val repo = packageRepoWithVersions(remotesRoot, "calculator-package", "example.calculator", listOf("1.0.0", "2.0.0"))
        val catalog = catalogRepo(remotesRoot, emptyMap())
        addReference(catalog, "example.calculator", "1.0.0", repo)
        addReference(catalog, "example.calculator", "2.0.0", repo)

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val resolved = registry(catalog, cacheDir).findAny("example.calculator")

        assertEquals("2.0.0", resolved?.version)
    }

    @Test
    fun `resolve picks the highest version satisfying the range, not necessarily the highest overall`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val repo =
            packageRepoWithVersions(remotesRoot, "calculator-package", "example.calculator", listOf("1.0.0", "1.5.0", "2.0.0"))
        val catalog = catalogRepo(remotesRoot, emptyMap())
        addReference(catalog, "example.calculator", "1.0.0", repo)
        addReference(catalog, "example.calculator", "1.5.0", repo)
        addReference(catalog, "example.calculator", "2.0.0", repo)

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val resolved = registry(catalog, cacheDir).resolve("example.calculator", "^1.0.0")

        assertEquals("1.5.0", resolved.version)
    }

    @Test
    fun `resolve throws a clear error naming the available versions when none satisfy the range`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val repo = packageRepoWithVersions(remotesRoot, "calculator-package", "example.calculator", listOf("1.0.0", "2.0.0"))
        val catalog = catalogRepo(remotesRoot, emptyMap())
        addReference(catalog, "example.calculator", "1.0.0", repo)
        addReference(catalog, "example.calculator", "2.0.0", repo)

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val exception =
            assertFailsWith<IllegalStateException> { registry(catalog, cacheDir).resolve("example.calculator", "^3.0.0") }

        assertTrue(exception.message!!.contains("example.calculator"))
        assertTrue(exception.message!!.contains("1.0.0"))
        assertTrue(exception.message!!.contains("2.0.0"))
        assertTrue(exception.message!!.contains("^3.0.0"))
    }

    @Test
    fun `a version only fetches its own worktree, not every candidate version`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val repo = packageRepoWithVersions(remotesRoot, "calculator-package", "example.calculator", listOf("1.0.0", "1.5.0", "2.0.0"))
        val catalog = catalogRepo(remotesRoot, emptyMap())
        addReference(catalog, "example.calculator", "1.0.0", repo)
        addReference(catalog, "example.calculator", "1.5.0", repo)
        addReference(catalog, "example.calculator", "2.0.0", repo)

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        registry(catalog, cacheDir).resolve("example.calculator", "^1.0.0")

        assertTrue(File(cacheDir, "example.calculator/v1.5.0/.git").exists(), "Expected the selected version to be fetched")
        assertFalse(File(cacheDir, "example.calculator/v1.0.0").exists(), "Expected a non-selected version not to be fetched")
        assertFalse(File(cacheDir, "example.calculator/v2.0.0").exists(), "Expected a non-selected version not to be fetched")
    }

    @Test
    fun `strips the configured prefix for catalog and cache paths, but keeps the full name as the public identity`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        // The package's own manifest/namespace has no "ba." prefix at all -
        // the prefix is purely a routing key, not part of the package's own identity.
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir, prefix = "ba.")

        val resolved = registry.findAny("ba.calculator")

        assertEquals("ba.calculator", resolved?.packageName)
        // installSubpath is the registry's own name now - shared by every package it resolves,
        // regardless of prefix - not "ba/calculator" (prefix + local name).
        assertEquals("test", resolved?.installSubpath)
        assertTrue(File(cacheDir, "calculator/_bare.git/HEAD").exists())
        assertTrue(File(cacheDir, "calculator/v1.0.0/.git").exists())
        assertFalse(File(cacheDir, "ba.calculator").exists())
    }

    @Test
    fun `rejects a package name that doesn't start with the configured prefix`() {
        val remotesRoot = createTempDirectory("pope-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("pope-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir, prefix = "ba.")

        assertFailsWith<IllegalArgumentException> { registry.findAny("cw.calculator") }
    }
}
