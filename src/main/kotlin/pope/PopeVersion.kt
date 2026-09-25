package pope

object PopeVersion {
    fun current(): String? = PopePlugin::class.java.`package`.implementationVersion

    /**
     * Major-only comparison, tolerant of non-strict-semver strings (e.g. "1.2.0-SNAPSHOT") -
     * pope.version.SemVer.parse requires an exact X.Y.Z match and would throw on those, which would
     * break this check for every local includeBuild dev loop (the default version there).
     * Null/unparseable on either side means "can't tell", not a mismatch.
     */
    fun majorMismatch(installed: String?, declared: String?): Boolean {
        val i = installed?.substringBefore('.')?.toIntOrNull()
        val d = declared?.substringBefore('.')?.toIntOrNull()
        return i != null && d != null && i != d
    }
}
