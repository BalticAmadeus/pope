package pope.registry

/** Routes a package_name to one registry by longest matching prefix. No match is a loud error, not a silent fallback. */
class PrefixRoutingRegistry(private val delegatesByPrefix: Map<String, Registry>) : Registry {
    private fun route(packageName: String): Registry =
        delegatesByPrefix.entries
            .filter { (prefix, _) -> packageName.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.value
            ?: throw IllegalStateException(
                "No configured registry prefix matches \"$packageName\" " +
                    "(configured prefixes: ${delegatesByPrefix.keys.joinToString(", ")})",
            )

    override fun resolve(packageName: String, versionSpec: String): ResolvedPackage =
        route(packageName).resolve(packageName, versionSpec)

    override fun findAny(packageName: String): ResolvedPackage? = route(packageName).findAny(packageName)
}
