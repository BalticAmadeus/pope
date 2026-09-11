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
    fun hash(dir: File): String {
        require(dir.isDirectory) { "Not a directory: ${dir.path}" }

        val manifest = StringBuilder()
        dir
            .walkTopDown()
            .filter { it.isFile }
            .map { file -> file.relativeTo(dir).invariantSeparatorsPath to file }
            .sortedBy { (relativePath, _) -> relativePath }
            .forEach { (relativePath, file) ->
                manifest.append(sha256Hex(file.readBytes())).append("  ").append(relativePath).append('\n')
            }

        return "sha256:" + sha256Hex(manifest.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
