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
            ensureWorktree(bareRepoDir, worktreeDir, packageName, repoUrl, ref)
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
            repoUrl = repoUrl,
            ref = ref,
        )
    }

    private fun ensureBareRepo(bareRepoDir: File, repoUrl: String) {
        if (File(bareRepoDir, "HEAD").exists()) return

        bareRepoDir.parentFile?.mkdirs()
        GitCli.run(null, "clone", "--bare", repoUrl, bareRepoDir.path)
    }

    private fun ensureWorktree(bareRepoDir: File, worktreeDir: File, packageName: String, repoUrl: String, ref: String) {
        if (isWorktreeCheckedOutAt(bareRepoDir, worktreeDir, ref)) {
            failIfTagMoved(packageName, repoUrl, ref, worktreeDir)
            return
        }

        if (!refExistsLocally(bareRepoDir, ref)) {
            GitCli.run(bareRepoDir, "fetch", "--tags", "--force", "origin")
            check(refExistsLocally(bareRepoDir, ref)) {
                "ref \"$ref\" does not exist in $repoUrl (no such tag, branch or commit)"
            }
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

    /**
     * A cached ref is reused without fetching, so a tag that was moved on the remote after it was
     * cached would silently keep serving the old commit. Tags are meant to be immutable, so fail
     * loudly instead. Skipped when the remote can't be reached (offline use) or when the ref isn't
     * a tag (a branch or commit has no "moved" notion here).
     */
    private fun failIfTagMoved(packageName: String, repoUrl: String, ref: String, worktreeDir: File) {
        val remoteCommit = remoteTagCommit(repoUrl, ref) ?: return
        val cachedCommit = GitCli.run(worktreeDir, "rev-parse", "HEAD").trim()
        check(cachedCommit == remoteCommit) {
            "Tag \"$ref\" of \"$packageName\" has moved: the cached copy is at ${cachedCommit.take(7)}, but " +
                "$repoUrl now has it at ${remoteCommit.take(7)}. Tags should not change after publishing - " +
                "publish a new version, or delete ${worktreeDir.parentFile.path} to use the moved tag."
        }
    }

    /** The commit a remote tag points at, or null if the remote is unreachable or has no such tag. */
    private fun remoteTagCommit(repoUrl: String, ref: String): String? {
        val output =
            runCatching { GitCli.run(null, "ls-remote", repoUrl, "refs/tags/$ref", "refs/tags/$ref^{}") }
                .getOrNull() ?: return null
        val shaByRef =
            output.lines()
                .filter { it.isNotBlank() }
                .associate { line -> line.substringAfter('\t') to line.substringBefore('\t') }
        // An annotated tag lists the tag object itself, plus the commit it points at under "^{}".
        return shaByRef["refs/tags/$ref^{}"] ?: shaByRef["refs/tags/$ref"]
    }

    private fun refExistsLocally(bareRepoDir: File, ref: String): Boolean =
        runCatching { GitCli.run(bareRepoDir, "rev-parse", "--verify", "$ref^{commit}") }.isSuccess

    /** Refs are almost always plain tags (e.g. "v1.0.2"), but sanitize defensively. */
    private fun sanitizeRefForPath(ref: String): String = ref.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
