package pope.manifest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackageNameInferrerTest {
    @Test
    fun `infers the namespace shared by every cls file under the source root`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()
        File(dir, "example/closer/Closer.cls").apply { parentFile.mkdirs() }.writeText(
            "class example.closer.Closer:\nend class.\n",
        )
        File(dir, "example/closer/OtherThing.cls").writeText(
            "class example.closer.OtherThing:\nend class.\n",
        )

        assertEquals("example.closer", PackageNameInferrer.infer(dir))
    }

    @Test
    fun `throws when no cls files are found`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()

        assertFailsWith<IllegalStateException> { PackageNameInferrer.infer(dir) }
    }

    @Test
    fun `throws when cls files disagree on namespace`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()
        File(dir, "example/one/One.cls").apply { parentFile.mkdirs() }.writeText(
            "class example.one.One:\nend class.\n",
        )
        File(dir, "example/two/Two.cls").apply { parentFile.mkdirs() }.writeText(
            "class example.two.Two:\nend class.\n",
        )

        assertFailsWith<IllegalStateException> { PackageNameInferrer.infer(dir) }
    }
}
