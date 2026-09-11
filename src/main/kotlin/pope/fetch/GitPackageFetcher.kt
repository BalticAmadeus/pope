package pope.fetch

import pope.manifest.ManifestReader
import pope.registry.ResolvedPackage
import java.io.File

/**
 * Fetches one package repo at a given ref. Shared by CatalogRegistry and
 * direct-source deps - only how repoUrl/ref were found differs.
 *
 * Caches as one bare clone per package (no working files, just git
 * history) plus one `git worktree` per ref used - a cached ref is a local
 * worktree add, a new one just an incremental fetch, never a re-clone.
 */
object GitPackageFetcher {
    fun fetch(packageName: String, repoUrl: String, ref: String, destDir: File): ResolvedPackage {
        val bareRepoDir = File(destDir, "_bare.git")
        val worktreeDir = File(destDir, sanitizeRefForPath(ref))

        try {
            ensureBareRepo(bareRepoDir, repoUrl)
            ensureWorktree(bareRepoDir, worktreeDir, ref)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to fetch \"$packageName\" from $repoUrl at ref \"$ref\": ${e.message}",
                e,
            )
        }

        val manifestFile = File(worktreeDir, "openedge-project.json")
        require(manifestFile.exists()) {
            "\"$packageName\" was fetched from $repoUrl (ref $ref) into ${worktreeDir.path}, " +
                "but it has no openedge-project.json"
        }
        val manifest = ManifestReader.read(manifestFile)

        val packageRoot =
            manifest.sourceRoots.firstOrNull()
                ?: throw IllegalStateException(
                    "\"$packageName\" at ${worktreeDir.path} has no buildPath source entry to serve as its package_root",
                )

        return ResolvedPackage(
            packageName = packageName,
            version = manifest.version,
            sourceDir = File(worktreeDir, packageRoot),
            projectDir = worktreeDir,
        )
    }

    private fun ensureBareRepo(bareRepoDir: File, repoUrl: String) {
        if (File(bareRepoDir, "HEAD").exists()) return

        bareRepoDir.parentFile?.mkdirs()
        GitCli.run(null, "clone", "--bare", repoUrl, bareRepoDir.path)
    }

    private fun ensureWorktree(bareRepoDir: File, worktreeDir: File, ref: String) {
        if (isWorktreeCheckedOutAt(bareRepoDir, worktreeDir, ref)) return

        if (!refExistsLocally(bareRepoDir, ref)) {
            GitCli.run(bareRepoDir, "fetch", "--tags", "--force", "origin")
        }

        if (worktreeDir.exists()) {
            // Stale (wrong ref, or a leftover failed run) - drop and re-add.
            runCatching { GitCli.run(bareRepoDir, "worktree", "remove", "--force", worktreeDir.path) }
            worktreeDir.deleteRecursively()
            GitCli.run(bareRepoDir, "worktree", "prune")
        }

        GitCli.run(bareRepoDir, "worktree", "add", "--force", worktreeDir.path, ref)
    }

    private fun isWorktreeCheckedOutAt(bareRepoDir: File, worktreeDir: File, ref: String): Boolean {
        if (!File(worktreeDir, ".git").exists()) return false

        val headCommit = runCatching { GitCli.run(worktreeDir, "rev-parse", "HEAD").trim() }.getOrNull() ?: return false
        val refCommit = runCatching { GitCli.run(bareRepoDir, "rev-parse", "$ref^{commit}").trim() }.getOrNull() ?: return false
        return headCommit == refCommit
    }

    private fun refExistsLocally(bareRepoDir: File, ref: String): Boolean =
        runCatching { GitCli.run(bareRepoDir, "rev-parse", "--verify", "$ref^{commit}") }.isSuccess

    /** Refs are almost always plain tags (e.g. "v1.0.2"), but sanitize defensively. */
    private fun sanitizeRefForPath(ref: String): String = ref.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
