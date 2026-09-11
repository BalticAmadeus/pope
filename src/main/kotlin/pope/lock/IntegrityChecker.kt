package pope.lock

/** Catches a registry silently serving different content for an already-locked version (e.g. a force-moved tag). */
object IntegrityChecker {
    fun verify(packageName: String, version: String, freshIntegrity: String, existingLock: Map<String, LockedPackage>) {
        val existing = existingLock[packageName] ?: return
        if (existing.version != version) return

        require(existing.integrity == freshIntegrity) {
            "Integrity check failed for \"$packageName\" $version: expected ${existing.integrity} " +
                "(from pope.lock) but the registry now resolves to $freshIntegrity. The content for this " +
                "already-locked version appears to have changed since it was last installed. If that's " +
                "expected, remove \"$packageName\" from pope.lock and reinstall."
        }
    }
}
