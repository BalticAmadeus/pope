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
    fun `infers from an INTERFACE declaration too, not just CLASS`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()
        File(dir, "example/closer/ICloser.cls").apply { parentFile.mkdirs() }.writeText(
            "INTERFACE example.closer.ICloser:\nEND INTERFACE.\n",
        )

        assertEquals("example.closer", PackageNameInferrer.infer(dir))
    }

    @Test
    fun `infers past modifiers like IMPLEMENTS and ABSTRACT between the name and the colon`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()
        File(dir, "example/closer/Closer.cls").apply { parentFile.mkdirs() }.writeText(
            "CLASS example.closer.Closer ABSTRACT IMPLEMENTS example.closer.ICloser:\nEND CLASS.\n",
        )

        assertEquals("example.closer", PackageNameInferrer.infer(dir))
    }

    @Test
    fun `throws when no cls files are found`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()

        assertFailsWith<IllegalStateException> { PackageNameInferrer.infer(dir) }
    }

    @Test
    fun `validateAgainstDeclared accepts classes nested under the declared package_name, not just an exact match`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()
        File(dir, "ba/util/Formatter/MyFormatter.cls").apply { parentFile.mkdirs() }.writeText(
            "CLASS ba.util.Formatter.MyFormatter IMPLEMENTS ba.util.Formatter.IFormatter:\nEND CLASS.\n",
        )
        File(dir, "ba/util/Substitutable/ASubstitutable.cls").apply { parentFile.mkdirs() }.writeText(
            "CLASS ba.util.Substitutable.ASubstitutable ABSTRACT:\nEND CLASS.\n",
        )

        // Doesn't throw - real-world case: a package's classes commonly live one or more
        // sub-namespaces below its own declared root (Formatter/, Substitutable/, etc.).
        PackageNameInferrer.validateAgainstDeclared(dir, "ba.util")
    }

    @Test
    fun `validateAgainstDeclared throws when a class's namespace is outside the declared package_name`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()
        File(dir, "wrong/namespace/Consumer.cls").apply { parentFile.mkdirs() }.writeText(
            "CLASS wrong.namespace.Consumer:\nEND CLASS.\n",
        )

        assertFailsWith<IllegalStateException> { PackageNameInferrer.validateAgainstDeclared(dir, "declared.name") }
    }

    @Test
    fun `validateAgainstDeclared throws when no cls files are found`() {
        val dir = createTempDirectory("package-name-inferrer-test").toFile()

        assertFailsWith<IllegalStateException> { PackageNameInferrer.validateAgainstDeclared(dir, "declared.name") }
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
