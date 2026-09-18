package pope.integrity

import java.io.File
import java.security.MessageDigest

/**
 * Content hash of a resolved package's source tree, for pope.lock's
 * "integrity" field. Modeled on Go's dirhash Hash1: hash each file, build
 * a manifest of "<sha256>  <relative path>" lines sorted by path, then
 * hash that - independent of walk order/OS path separators, sensitive to
 * renames too.
 */
object DirectoryHash {
    /** Every file under dir, relative-pathed and sorted - the same enumeration hash() hashes, reusable for file tracking. */
    fun relativeFiles(dir: File): List<String> {
        require(dir.isDirectory) { "Not a directory: ${dir.path}" }

        return dir
            .walkTopDown()
            .filter { it.isFile }
            .map { file -> file.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    fun hash(dir: File): String {
        val manifest = StringBuilder()
        relativeFiles(dir).forEach { relativePath ->
            manifest.append(sha256Hex(File(dir, relativePath).readBytes())).append("  ").append(relativePath).append('\n')
        }

        return "sha256:" + sha256Hex(manifest.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
