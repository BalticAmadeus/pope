package pope

import org.gradle.testkit.runner.GradleRunner
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the plugin can be applied the way a real, separate consumer repo
 * would - by plugin id + version, resolved from a Maven repository -
 * rather than via includeBuild(path) or TestKit's withPluginClasspath()
 * shortcut (which PopePluginFunctionalTest uses, and which bypasses real
 * plugin resolution entirely). This is what a 3-repo split actually needs
 * to work: a consumer with zero knowledge of where this repo's source
 * happens to live on disk.
 *
 * The version under test is published to mavenLocal() as a functionalTest
 * task dependency (see build.gradle.kts) - the actual hosting location
 * for real use (a git-repo-hosted Maven repo — see ADR-0008) is a
 * different repository pointing at the same kind of Maven-format folder,
 * so this proves the mechanism works without depending on that hosting
 * decision.
 */
class PublishedPluginFunctionalTest {
    @Test
    fun `a consumer applies the plugin by id and version, with no includeBuild and no knowledge of this repo's path`() {
        val pluginVersion =
            System.getProperty("popePluginVersion")
                ?: error("popePluginVersion system property not set - see build.gradle.kts's functionalTest task")

        val registryDir = createTempDirectory("pope-published-plugin-registry").toFile()
        File("src/functionalTest/resources/fixtures/greeter-package").copyRecursively(File(registryDir, "example.greeter"))
        val registryPath = registryDir.absolutePath.replace("\\", "/")

        val projectDir = createTempDirectory("pope-published-plugin-consumer").toFile()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenLocal()
                    // The plugin's own runtime dependency (org.json) still
                    // has to resolve from somewhere - mavenLocal() only
                    // has the plugin itself, not its third-party deps.
                    mavenCentral()
                }
            }
            rootProject.name = "published-consumer-fixture"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope") version "$pluginVersion"
            }

            pope {
                registryRoot.set(file("$registryPath"))
            }
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "published-consumer-fixture")
                .put("version", "1.0.0")
                .put("pope_package_name", "example.publishedconsumer")
                .put("pope_dependencies", JSONObject().put("example.greeter", "^1.0.0"))
                .put(
                    "buildPath",
                    org.json.JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                ).toString(2),
        )

        // Deliberately no withPluginClasspath() - that would inject this
        // build's classes directly and skip real Maven resolution, which
        // is exactly the thing this test needs to exercise for real.
        val installResult =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("popeInstall")
                .build()

        assertTrue(
            installResult.output.contains("Resolved 1 dependency:"),
            "Expected the published plugin to resolve example.greeter for real, got:\n${installResult.output}",
        )
        assertTrue(
            File(projectDir, "pope_packages/example.greeter/src/example/greeter/Greeter.cls").exists(),
            "Expected the resolved package's source to be copied into pope_packages",
        )
    }
}
