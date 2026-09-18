package pope.suggest

/** Thrown by PrefixRoutingRegistry.route() when a bare/dotted name matches no configured prefix at all. */
class NoRegistryPrefixMatchException(message: String) : IllegalStateException(message)
