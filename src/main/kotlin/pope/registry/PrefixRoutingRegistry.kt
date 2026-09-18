package pope.registry

import pope.suggest.DidYouMean
import pope.suggest.NameNotFoundException
import pope.suggest.NoRegistryPrefixMatchException

/** One configured registry: its DSL/properties label, its routing prefix, and the Registry that serves it. */
data class RegistryEntry(val name: String, val prefix: String, val registry: Registry)

/**
 * Routes a package_name to one registry: "registryName/localName" resolves explicitly against
 * that registry; anything else routes by longest matching prefix. No match is a loud error.
 */
class PrefixRoutingRegistry(private val entries: List<RegistryEntry>) : Registry {
    private val byName: Map<String, RegistryEntry> = entries.associateBy { it.name }
    private val delegatesByPrefix: Map<String, Registry> = entries.associateBy({ it.prefix }, { it.registry })

    private fun route(packageName: String): Registry =
        delegatesByPrefix.entries
            .filter { (prefix, _) -> packageName.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.value
            ?: throw NoRegistryPrefixMatchException(
                "No configured registry prefix matches \"$packageName\" " +
                    "(configured prefixes: ${delegatesByPrefix.keys.joinToString(", ")})",
            )

    /** Non-null only for an explicit "registryName/localName" name - the (registry, real full name) to resolve against. */
    private fun routeExplicit(packageName: String): Pair<Registry, String>? {
        val slashIndex = packageName.indexOf('/')
        if (slashIndex < 0) return null

        val registryName = packageName.substring(0, slashIndex)
        val localName = packageName.substring(slashIndex + 1)
        val entry =
            byName[registryName] ?: run {
                val suggestion = DidYouMean.suggest(registryName, byName.keys)
                throw NameNotFoundException(
                    "No registry named \"$registryName\" is configured " +
                        "(configured registry names: ${byName.keys.joinToString(", ")})" +
                        (suggestion?.let { " - did you mean \"$it\"?" } ?: ""),
                    suggestion = suggestion?.let { "$it/$localName" },
                )
            }
        return entry.registry to (entry.prefix + localName)
    }

    /** Every registry that has this bare local name (Registry.hasAny - no package fetch), paired with its "registryName/localName" form. */
    fun findAllMatches(localName: String): List<Pair<RegistryEntry, String>> =
        entries
            .filter { entry -> entry.registry.hasAny(entry.prefix + localName) }
            .map { entry -> entry to "${entry.name}/$localName" }

    /** First "did you mean X?" match across every registry (Registry.suggestAny), in "registryName/localName" form. */
    fun suggestAcrossRegistries(localName: String): String? =
        entries.firstNotNullOfOrNull { entry -> entry.registry.suggestAny(entry.prefix + localName)?.let { "${entry.name}/$it" } }

    override fun resolve(packageName: String, versionSpec: String): ResolvedPackage {
        val (registry, fullName) = routeExplicit(packageName) ?: (route(packageName) to packageName)
        return registry.resolve(fullName, versionSpec)
    }

    override fun findAny(packageName: String): ResolvedPackage? {
        val (registry, fullName) = routeExplicit(packageName) ?: (route(packageName) to packageName)
        return registry.findAny(fullName)
    }
}
