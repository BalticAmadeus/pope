package pope.integrity

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DirectoryHashTest {
    private fun dirWith(vararg files: Pair<String, String>): File {
        val dir = createTempDirectory("pope-dirhash-test").toFile()
        for ((relativePath, content) in files) {
            val file = File(dir, relativePath)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        return dir
    }

    @Test
    fun `is prefixed with sha256`() {
        val dir = dirWith("a.txt" to "hello")

        assertTrue(DirectoryHash.hash(dir).startsWith("sha256:"))
    }

    @Test
    fun `same content produces the same hash`() {
        val dirA = dirWith("a.txt" to "hello", "sub/b.txt" to "world")
        val dirB = dirWith("a.txt" to "hello", "sub/b.txt" to "world")

        assertEquals(DirectoryHash.hash(dirA), DirectoryHash.hash(dirB))
    }

    @Test
    fun `different file content produces a different hash`() {
        val dirA = dirWith("a.txt" to "hello")
        val dirB = dirWith("a.txt" to "goodbye")

        assertNotEquals(DirectoryHash.hash(dirA), DirectoryHash.hash(dirB))
    }

    @Test
    fun `a renamed file produces a different hash even with identical content`() {
        val dirA = dirWith("a.txt" to "hello")
        val dirB = dirWith("b.txt" to "hello")

        assertNotEquals(DirectoryHash.hash(dirA), DirectoryHash.hash(dirB))
    }

    @Test
    fun `an extra file produces a different hash`() {
        val dirA = dirWith("a.txt" to "hello")
        val dirB = dirWith("a.txt" to "hello", "b.txt" to "world")

        assertNotEquals(DirectoryHash.hash(dirA), DirectoryHash.hash(dirB))
    }
}
