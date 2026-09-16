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
        val registry =
            PrefixRoutingRegistry(
                listOf(RegistryEntry("ba", "ba.", FakeRegistry("ba")), RegistryEntry("cw", "cw.", FakeRegistry("cw"))),
            )

        assertEquals("ba", registry.resolve("ba.calculator", "^1.0.0").sourceDir.name)
        assertEquals("cw", registry.resolve("cw.calculator", "^1.0.0").sourceDir.name)
    }

    @Test
    fun `routes by the longest matching prefix when prefixes overlap`() {
        val registry =
            PrefixRoutingRegistry(
                listOf(
                    RegistryEntry("ba", "ba.", FakeRegistry("ba")),
                    RegistryEntry("ba-sub", "ba.sub.", FakeRegistry("ba-sub")),
                ),
            )

        assertEquals("ba-sub", registry.resolve("ba.sub.calculator", "^1.0.0").sourceDir.name)
        assertEquals("ba", registry.resolve("ba.calculator", "^1.0.0").sourceDir.name)
    }

    @Test
    fun `throws a clear error listing configured prefixes when nothing matches`() {
        val registry =
            PrefixRoutingRegistry(
                listOf(RegistryEntry("ba", "ba.", FakeRegistry("ba")), RegistryEntry("cw", "cw.", FakeRegistry("cw"))),
            )

        val exception = assertFailsWith<IllegalStateException> { registry.findAny("acme.calculator") }

        assertTrue(exception.message!!.contains("acme.calculator"))
        assertTrue(exception.message!!.contains("ba."))
        assertTrue(exception.message!!.contains("cw."))
    }

    @Test
    fun `a matched registry returning null is passed through, not a routing error`() {
        val registry = PrefixRoutingRegistry(listOf(RegistryEntry("ba", "ba.", FakeRegistry("ba"))))

        assertNull(registry.findAny("ba.missing.package"))
    }

    // --- explicit "registryName/localName" selection ---

    @Test
    fun `an explicit registryName-localName name reconstructs the real prefixed name and delegates`() {
        val registry = PrefixRoutingRegistry(listOf(RegistryEntry("registry-ba", "ba.", FakeRegistry("ba"))))

        val resolved = registry.resolve("registry-ba/calculator", "^1.0.0")

        assertEquals("ba.calculator", resolved.packageName)
        assertEquals("ba", resolved.sourceDir.name)
    }

    @Test
    fun `an explicit registryName-localName name works through findAny too, with no version`() {
        val registry = PrefixRoutingRegistry(listOf(RegistryEntry("registry-ba", "ba.", FakeRegistry("ba"))))

        val resolved = registry.findAny("registry-ba/calculator")

        assertEquals("ba.calculator", resolved?.packageName)
    }

    @Test
    fun `an unknown registry name in explicit syntax throws a clear error listing configured names`() {
        val registry =
            PrefixRoutingRegistry(
                listOf(RegistryEntry("registry-ba", "ba.", FakeRegistry("ba")), RegistryEntry("cw", "cw.", FakeRegistry("cw"))),
            )

        val exception = assertFailsWith<IllegalStateException> { registry.resolve("nope/calculator", "^1.0.0") }

        assertTrue(exception.message!!.contains("nope"))
        assertTrue(exception.message!!.contains("registry-ba"))
        assertTrue(exception.message!!.contains("cw"))
    }

    @Test
    fun `a slash-free name still routes by prefix even when configured entries also have names`() {
        val registry = PrefixRoutingRegistry(listOf(RegistryEntry("registry-ba", "ba.", FakeRegistry("ba"))))

        assertEquals("ba.calculator", registry.resolve("ba.calculator", "^1.0.0").packageName)
    }
}
