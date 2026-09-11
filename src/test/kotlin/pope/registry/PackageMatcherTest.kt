package pope.registry

import pope.manifest.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageMatcherTest {
    private fun manifest(packageName: String) = Manifest(name = "$packageName-project", version = "1.0.0", packageName = packageName)

    @Test
    fun `returns the single candidate matching by package_name`() {
        val candidates = listOf("a" to manifest("example.calculator"), "b" to manifest("example.greeter"))

        val result = PackageMatcher.selectUnique(candidates, "example.greeter", describeLocation = { it })

        assertEquals("b" to manifest("example.greeter"), result)
    }

    @Test
    fun `returns null when nothing matches`() {
        val candidates = listOf("a" to manifest("example.calculator"))

        assertNull(PackageMatcher.selectUnique(candidates, "example.other", describeLocation = { it }))
    }

    @Test
    fun `throws naming every location when more than one candidate matches`() {
        val candidates = listOf("loc-a" to manifest("example.greeter"), "loc-b" to manifest("example.greeter"))

        val exception =
            assertFailsWith<IllegalStateException> {
                PackageMatcher.selectUnique(candidates, "example.greeter", describeLocation = { it })
            }

        assertTrue(exception.message!!.contains("loc-a"))
        assertTrue(exception.message!!.contains("loc-b"))
    }
}
