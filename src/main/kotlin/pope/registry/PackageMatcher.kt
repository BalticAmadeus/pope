package pope.registry

import pope.manifest.Manifest

/** Finds the one candidate whose manifest declares packageName, failing loudly if more than one does. */
object PackageMatcher {
    fun <T> selectUnique(
        candidates: List<Pair<T, Manifest>>,
        packageName: String,
        describeLocation: (T) -> String,
    ): Pair<T, Manifest>? {
        val matches = candidates.filter { (_, manifest) -> manifest.packageName == packageName }

        if (matches.size > 1) {
            val locations = matches.joinToString(", ") { (location, _) -> describeLocation(location) }
            throw IllegalStateException(
                "Multiple packages named \"$packageName\" found: $locations. " +
                    "package_name must be unique across the registry.",
            )
        }

        return matches.singleOrNull()
    }
}
