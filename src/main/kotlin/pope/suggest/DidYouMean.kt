package pope.suggest

/**
 * "Did you mean X?" - the closest match to a not-found name among known
 * candidates, by edit distance, for typo-friendly error messages. Returns
 * null rather than a wild guess when nothing is close enough to be useful.
 */
object DidYouMean {
    fun suggest(input: String, candidates: Collection<String>): String? {
        if (candidates.isEmpty()) return null

        val maxDistance = maxOf(2, input.length / 3)
        return candidates
            .map { candidate -> candidate to levenshteinDistance(input, candidate) }
            .filter { (_, distance) -> distance <= maxDistance }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    /** Classic edit-distance DP: fewest single-character insert/delete/substitute ops to turn a into b. */
    private fun levenshteinDistance(a: String, b: String): Int {
        val previousRow = IntArray(b.length + 1) { it }
        val currentRow = IntArray(b.length + 1)

        for (i in 1..a.length) {
            currentRow[0] = i
            for (j in 1..b.length) {
                currentRow[j] =
                    if (a[i - 1] == b[j - 1]) {
                        previousRow[j - 1]
                    } else {
                        1 + minOf(previousRow[j], currentRow[j - 1], previousRow[j - 1])
                    }
            }
            previousRow.indices.forEach { previousRow[it] = currentRow[it] }
        }

        return previousRow[b.length]
    }
}
