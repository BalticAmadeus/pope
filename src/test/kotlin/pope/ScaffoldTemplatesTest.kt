package pope

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards pope-init/pope-init.bat's inline settings.gradle.kts/build.gradle.kts content (there's no
 * template file anymore - they write it directly) against regressing back to the old
 * includeBuild(popeToolPath) model - a plain string check on the scripts themselves, cheaper than
 * actually running them (see PublishedPluginFunctionalTest for the end-to-end proof that
 * coordinate-based resolution actually works).
 */
class ScaffoldTemplatesTest {
    private val bashScript = File("pope-init").readText()
    private val batScript = File("pope-init.bat").readText()

    @Test
    fun `pope-init writes a settings block that resolves the plugin from the published Maven repo, not includeBuild`() {
        for (script in listOf(bashScript, batScript)) {
            assertFalse(script.contains("includeBuild"), "Should no longer use includeBuild")
            assertFalse(script.contains("popeToolPath"), "Should no longer reference popeToolPath")
            assertTrue(script.contains("https://balticamadeus.github.io/pope/"))
        }
    }

    @Test
    fun `pope-init writes a build block that applies the plugin with an explicit version`() {
        assertTrue(bashScript.contains("id(\"io.github.balticamadeus.pope\") version \"\$POPE_VERSION\""))
        assertTrue(batScript.contains("id(\"io.github.balticamadeus.pope\"^) version \"%POPE_VERSION%\""))
    }
}
