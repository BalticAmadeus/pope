package pope.registry

/** One configured registry: its DSL/properties label, its routing prefix, and the Registry that serves it. */
data class RegistryEntry(val name: String, val prefix: String, val registry: Registry)

/**
 * Routes a package_name to one registry, two ways:
 *  - "registryName/localName" - explicit: bypasses prefix matching entirely, resolves directly
 *    against the named registry, reconstructing its real prefixed name ("prefix" + "localName")
 *    before delegating - so the delegate registry sees a normally-prefixed name exactly as it
 *    would from the implicit path below, unchanged.
 *  - anything else - implicit: routed by longest matching prefix. No match is a loud error, not a
 *    silent fallback.
 */
class PrefixRoutingRegistry(private val entries: List<RegistryEntry>) : Registry {
    private val byName: Map<String, RegistryEntry> = entries.associateBy { it.name }
    private val delegatesByPrefix: Map<String, Registry> = entries.associateBy({ it.prefix }, { it.registry })

    private fun route(packageName: String): Registry =
        delegatesByPrefix.entries
            .filter { (prefix, _) -> packageName.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.value
            ?: throw IllegalStateException(
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
            byName[registryName]
                ?: throw IllegalStateException(
                    "No registry named \"$registryName\" is configured " +
                        "(configured registry names: ${byName.keys.joinToString(", ")})",
                )
        return entry.registry to (entry.prefix + localName)
    }

    override fun resolve(packageName: String, versionSpec: String): ResolvedPackage {
        val (registry, fullName) = routeExplicit(packageName) ?: (route(packageName) to packageName)
        return registry.resolve(fullName, versionSpec)
    }

    override fun findAny(packageName: String): ResolvedPackage? {
        val (registry, fullName) = routeExplicit(packageName) ?: (route(packageName) to packageName)
        return registry.findAny(fullName)
    }
}
