package pope.registry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeRegistry(private val label: String) : Registry {
    override fun resolve(packageName: String, versionSpec: String) =
        ResolvedPackage(packageName, "1.0.0", File(label), File(label))

    override fun findAny(packageName: String): ResolvedPackage? =
        if (packageName.contains("missing")) null else ResolvedPackage(packageName, "1.0.0", File(label), File(label))
}

class PrefixRoutingRegistryTest {
    @Test
    fun `routes to the registry whose prefix matches`() {
        val registry = PrefixRoutingRegistry(mapOf("ba." to FakeRegistry("ba"), "cw." to FakeRegistry("cw")))

        assertEquals("ba", registry.resolve("ba.calculator", "^1.0.0").sourceDir.name)
        assertEquals("cw", registry.resolve("cw.calculator", "^1.0.0").sourceDir.name)
    }

    @Test
    fun `routes by the longest matching prefix when prefixes overlap`() {
        val registry =
            PrefixRoutingRegistry(mapOf("ba." to FakeRegistry("ba"), "ba.sub." to FakeRegistry("ba-sub")))

        assertEquals("ba-sub", registry.resolve("ba.sub.calculator", "^1.0.0").sourceDir.name)
        assertEquals("ba", registry.resolve("ba.calculator", "^1.0.0").sourceDir.name)
    }

    @Test
    fun `throws a clear error listing configured prefixes when nothing matches`() {
        val registry = PrefixRoutingRegistry(mapOf("ba." to FakeRegistry("ba"), "cw." to FakeRegistry("cw")))

        val exception = assertFailsWith<IllegalStateException> { registry.findAny("acme.calculator") }

        assertTrue(exception.message!!.contains("acme.calculator"))
        assertTrue(exception.message!!.contains("ba."))
        assertTrue(exception.message!!.contains("cw."))
    }

    @Test
    fun `a matched registry returning null is passed through, not a routing error`() {
        val registry = PrefixRoutingRegistry(mapOf("ba." to FakeRegistry("ba")))

        assertNull(registry.findAny("ba.missing.package"))
    }
}
