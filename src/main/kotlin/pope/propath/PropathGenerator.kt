package pope.propath

import pope.manifest.Manifest
import java.io.File

/**
 * PROPATH is buildPath's "source" roots resolved to absolute paths, own
 * source first (docs/spec/propath-generation.md). includeTests appends
 * "test" roots too - never a dependency's, only this project's own.
 */
object PropathGenerator {
    fun generate(projectDir: File, manifest: Manifest, includeTests: Boolean = false): List<String> {
        val roots = if (includeTests) manifest.sourceRoots + manifest.testRoots else manifest.sourceRoots
        return roots.map { path -> File(projectDir, path).absolutePath }
    }
}
