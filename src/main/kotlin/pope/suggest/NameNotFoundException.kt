package pope.suggest

/**
 * A registry/package name wasn't found, but DidYouMean found a close enough candidate to offer -
 * [suggestion] is the ready-to-use corrected name (already in whatever form the caller passed
 * in - fully-qualified, "registryName/localName", etc.), not just the bare candidate word, so a
 * caller can retry directly with it. Null when nothing was close enough to suggest.
 */
class NameNotFoundException(message: String, val suggestion: String?) : IllegalStateException(message)
