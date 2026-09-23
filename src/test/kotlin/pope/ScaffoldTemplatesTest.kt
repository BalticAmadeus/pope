package pope

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards scaffoldProject's settings.gradle.kts.template against regressing back to the old
 * includeBuild(popeToolPath)/gradle.properties model - a plain string check on the template file
 * itself, cheaper than actually running scaffoldProject (see PublishedPluginFunctionalTest for the
 * end-to-end proof that coordinate-based resolution actually works).
 */
class ScaffoldTemplatesTest {
    private val settingsTemplate = File("scaffold/templates/settings.gradle.kts.template").readText()
    private val buildTemplate = File("scaffold/templates/build.gradle.kts.template").readText()

    @Test
    fun `settings template resolves the plugin from the published Maven repo, not includeBuild`() {
        assertFalse(settingsTemplate.contains("includeBuild"), "Should no longer use includeBuild")
        assertFalse(settingsTemplate.contains("popeToolPath"), "Should no longer reference popeToolPath")
        assertTrue(settingsTemplate.contains("https://balticamadeus.github.io/pope/"))
    }

    @Test
    fun `build template applies the plugin with an explicit version`() {
        assertTrue(buildTemplate.contains("id(\"io.github.balticamadeus.pope\") version \"{{POPE_VERSION}}\""))
    }
}
