package pope

object PopeVersion {
    fun current(): String? = PopePlugin::class.java.`package`.implementationVersion

    // Major-only, tolerant of "1.2.0-SNAPSHOT" (SemVer.parse would throw on that). Unparseable/null means "can't tell".
    fun majorMismatch(installed: String?, declared: String?): Boolean {
        val i = installed?.substringBefore('.')?.toIntOrNull()
        val d = declared?.substringBefore('.')?.toIntOrNull()
        return i != null && d != null && i != d
    }
}
