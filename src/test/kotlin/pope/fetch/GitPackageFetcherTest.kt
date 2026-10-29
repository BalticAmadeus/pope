package pope.fetch

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitPackageFetcherTest {
    private fun git(dir: File, vararg args: String) = GitCli.run(dir, *args)

    private fun packageRepo(root: File, folderName: String, packageName: String, version: String): File {
        val dir = File(root, folderName)
        dir.mkdirs()
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "pope-test@example.com")
        git(dir, "config", "user.name", "pope test")
        File(dir, "openedge-project.json").writeText(
            """
            {
              "name": "$folderName-project",
              "version": "$version",
              "popePackageName": "$packageName",
              "popeDependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(dir, "src").mkdirs()
        File(dir, "src/marker.i").writeText("/* $packageName source marker */")
        git(dir, "add", "-A")
        git(dir, "commit", "-m", "initial")
        git(dir, "tag", "v$version")
        return dir
    }

    @Test
    fun `clones the repo at the given ref and builds a ResolvedPackage from its manifest`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")

        val resolved = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        assertEquals("ba.greeter", resolved.packageName)
        assertEquals("1.0.1", resolved.version)
        assertEquals("src", resolved.sourceDir.name)
        assertEquals(File(destDir, "v1.0.1"), resolved.projectDir)
        assertEquals(true, File(destDir, "_bare.git/HEAD").exists())
    }

    @Test
    fun `reuses an already-checked-out worktree instead of fetching again`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")

        GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)
        val second = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        assertEquals("1.0.1", second.version)
    }

    @Test
    fun `a second version of the same package reuses the bare cache and adds a second worktree`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        File(repo, "openedge-project.json").writeText(
            """
            {
              "name": "greeter-repo-project",
              "version": "1.0.2",
              "popePackageName": "greeter",
              "popeDependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(repo, "src/marker.i").writeText("/* greeter source marker v2 */")
        git(repo, "add", "-A")
        git(repo, "commit", "-m", "bump to 1.0.2")
        git(repo, "tag", "v1.0.2")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")

        val first = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)
        val second = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.2", destDir)

        assertEquals("1.0.1", first.version)
        assertEquals("1.0.2", second.version)
        assertEquals(true, File(destDir, "v1.0.1/.git").exists())
        assertEquals(true, File(destDir, "v1.0.2/.git").exists())
    }

    @Test
    fun `fails loudly when a cached tag was moved on the remote after it was cached`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")
        GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        File(repo, "src/marker.i").writeText("/* changed after the tag was first fetched */")
        git(repo, "add", "-A")
        git(repo, "commit", "-m", "change after tagging")
        git(repo, "tag", "-f", "v1.0.1")

        val error =
            assertFailsWith<IllegalStateException> {
                GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)
            }
        assertTrue(error.message!!.contains("has moved"), "Expected a moved-tag message, got: ${error.message}")
        assertTrue(error.message!!.contains("v1.0.1"), "Expected the tag name in the message, got: ${error.message}")
    }

    @Test
    fun `an annotated tag that has not moved is reused without error`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        git(repo, "tag", "-a", "v1.0.1-annotated", "-m", "annotated release")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")

        GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1-annotated", destDir)
        val second = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1-annotated", destDir)

        assertEquals("1.0.1", second.version)
    }

    @Test
    fun `a cached ref still resolves when the remote is unreachable`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")
        GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        val unreachable = File(remotesRoot, "no-such-repo").absolutePath
        val second = GitPackageFetcher.fetch("ba.greeter", unreachable, "v1.0.1", destDir)

        assertEquals("1.0.1", second.version)
    }

    @Test
    fun `a ref that does not exist on the remote fails with a clear message naming the ref`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")

        val error =
            assertFailsWith<IllegalStateException> {
                GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v9.9.9", destDir)
            }
        assertTrue(error.message!!.contains("v9.9.9"), "Expected the missing ref in the message, got: ${error.message}")
        assertTrue(error.message!!.contains("does not exist"), "Expected a clear not-found message, got: ${error.message}")
    }

    @Test
    fun `recovers when an interrupted run left a worktree folder with no git checkout in it`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")
        GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        val worktreeDir = File(destDir, "v1.0.1")
        File(worktreeDir, ".git").delete()
        File(worktreeDir, "leftover.txt").writeText("half-written")

        val resolved = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        assertEquals("1.0.1", resolved.version)
        assertTrue(File(destDir, "v1.0.1/.git").exists(), "Expected the worktree to be checked out again")
        assertTrue(!File(worktreeDir, "leftover.txt").exists(), "Expected the leftover file to be cleared")
    }

    @Test
    fun `recovers when the cached worktree folder was deleted by hand`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")
        GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        File(destDir, "v1.0.1").deleteRecursively()

        val resolved = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        assertEquals("1.0.1", resolved.version)
        assertTrue(File(destDir, "v1.0.1/.git").exists(), "Expected the worktree to be checked out again")
    }

    @Test
    fun `recovers when an interrupted clone left a partial bare repo folder behind`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "greeter")

        // What a clone killed part-way leaves behind: the folder exists, but there is no HEAD yet.
        File(destDir, "_bare.git/objects").mkdirs()
        File(destDir, "_bare.git/config").writeText("[core]\n")

        val resolved = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        assertEquals("1.0.1", resolved.version)
        assertTrue(File(destDir, "_bare.git/HEAD").exists(), "Expected the bare repo to be cloned again")
    }

    @Test
    fun `throws a clear error when the cloned repo has no openedge-project json`() {
        val remotesRoot = createTempDirectory("pope-fetcher-remotes").toFile()
        val dir = File(remotesRoot, "empty-repo").apply { mkdirs() }
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "pope-test@example.com")
        git(dir, "config", "user.name", "pope test")
        File(dir, "README.md").writeText("nothing here")
        git(dir, "add", "-A")
        git(dir, "commit", "-m", "initial")
        git(dir, "tag", "v1.0.0")
        val destDir = File(createTempDirectory("pope-fetcher-dest").toFile(), "empty")

        assertFailsWith<IllegalArgumentException> {
            GitPackageFetcher.fetch("ba.empty", dir.absolutePath, "v1.0.0", destDir)
        }
    }
}
