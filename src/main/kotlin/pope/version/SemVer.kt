package pope.version

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val EXACT = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

        fun parse(value: String): SemVer {
            val match =
                EXACT.matchEntire(value)
                    ?: throw IllegalArgumentException("Not a valid exact version (expected X.Y.Z): $value")
            val (major, minor, patch) = match.destructured
            return SemVer(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/**
 * npm-style caret ranges (v1 supports only these, no other syntax):
 * ^1.2.3 -> >=1.2.3 <2.0.0
 * ^0.2.3 -> >=0.2.3 <0.3.0   (stricter once major is 0)
 * ^0.0.3 -> >=0.0.3 <0.0.4   (stricter still once major and minor are 0)
 */
object CaretRange {
    private val CARET = Regex("""^\^(\d+)\.(\d+)\.(\d+)$""")

    fun satisfies(range: String, version: SemVer): Boolean {
        val match =
            CARET.matchEntire(range)
                ?: throw IllegalArgumentException("Only caret ranges (^X.Y.Z) are supported for v1, got: $range")
        val (majorStr, minorStr, patchStr) = match.destructured
        val base = SemVer(majorStr.toInt(), minorStr.toInt(), patchStr.toInt())

        if (version < base) return false

        val upperExclusive =
            when {
                base.major > 0 -> SemVer(base.major + 1, 0, 0)
                base.minor > 0 -> SemVer(0, base.minor + 1, 0)
                else -> SemVer(0, 0, base.patch + 1)
            }

        return version < upperExclusive
    }
}
