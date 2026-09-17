package pope.trust

import org.gradle.api.internal.tasks.userinput.UserInputHandler

/**
 * Confirms with the user before installing a direct-source dependency -
 * one that bypasses the registry catalog entirely and points straight at
 * an arbitrary git repo. Goes through Gradle's own UserInputHandler
 * (internal API, but what Gradle's own built-in prompts - e.g. `gradle
 * init` - use) rather than raw System.in/System.console(), so the
 * question is properly synchronized with the console renderer instead of
 * colliding with it, and so it can go through the daemon's own supported
 * input-forwarding channel.
 */
object TrustPrompt {
    fun confirm(userInputHandler: UserInputHandler, packageKey: String, repoUrl: String, ref: String, path: List<String>): Boolean {
        val via = (path + packageKey).joinToString(" -> ")
        val question =
            """
            "$packageKey" is not from any configured registry - it's a direct-source dependency.

              via    : $via
              source : $repoUrl@$ref

            Do you trust this source and want to install it?
            """.trimIndent()
        // Null means no interactive input is available (e.g. non-interactive/CI) - decline rather
        // than silently proceeding; use -PpopeTrustAll there instead.
        return userInputHandler.askYesNoQuestion(question) ?: false
    }
}
