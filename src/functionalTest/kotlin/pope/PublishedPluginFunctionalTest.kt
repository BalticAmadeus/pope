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
                .put("popePackageName", "example.publishedconsumer")
                .put("popeDependencies", JSONObject().put("example.greeter", "^1.0.0"))
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

    /**
     * Proves the other half of bootstrapping a brand-new project without a pope clone: once the
     * plugin is applied by coordinate (as above), it can finish project setup itself via popeInit -
     * no scaffoldProject task from pope's own build.gradle.kts involved, and no filesystem access to
     * pope's own repo tree (its templates come from the plugin jar's bundled resources instead - see
     * src/main/resources/pope/scaffold/).
     */
    @Test
    fun `popeInit finishes project setup using only the published plugin, no pope repo checkout involved`() {
        val pluginVersion =
            System.getProperty("popePluginVersion")
                ?: error("popePluginVersion system property not set - see build.gradle.kts's functionalTest task")

        val projectDir = createTempDirectory("pope-init-consumer").toFile()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
            }
            rootProject.name = "popeinit-fixture"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope") version "$pluginVersion"
            }
            """.trimIndent(),
        )
        File(projectDir, "src/example/initfixture").mkdirs()
        File(projectDir, "src/example/initfixture/Widget.cls").writeText(
            "class example.initfixture.Widget:\nend class.\n",
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("popeInit")
                .build()

        assertTrue(
            result.output.contains("generated openedge-project.json"),
            "Expected popeInit to generate openedge-project.json, got:\n${result.output}",
        )
        val manifest = JSONObject(File(projectDir, "openedge-project.json").readText())
        assertTrue(manifest.getString("popePackageName") == "example.initfixture")
        assertTrue(File(projectDir, "pope").exists(), "Expected the per-project pope CLI to be copied in")
        assertTrue(File(projectDir, "pope.bat").exists(), "Expected the per-project pope.bat CLI to be copied in")
        assertTrue(
            File(projectDir, ".gitignore").readText().contains("pope_packages/"),
            "Expected .gitignore to be generated with pope_packages/ ignored",
        )
    }

    /**
     * popeVersion reads Package.getImplementationVersion() off the plugin's own class - only ever
     * set on a real packaged jar (see build.gradle.kts's "jar" task manifest), never when applied via
     * includeBuild/withPluginClasspath(). Only provable against a genuinely published-and-resolved
     * jar, same reason this whole file exists rather than PopePluginFunctionalTest.
     */
    @Test
    fun `popeVersion prints the version the plugin was actually resolved at`() {
        val pluginVersion =
            System.getProperty("popePluginVersion")
                ?: error("popePluginVersion system property not set - see build.gradle.kts's functionalTest task")

        val projectDir = createTempDirectory("pope-version-consumer").toFile()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
            }
            rootProject.name = "popeversion-fixture"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope") version "$pluginVersion"
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("popeVersion")
                .build()

        assertTrue(
            result.output.lines().any { it.trim() == pluginVersion },
            "Expected popeVersion to print \"$pluginVersion\", got:\n${result.output}",
        )
    }

    /**
     * The mismatch check compares against the *running* pope's own version (Package.
     * getImplementationVersion(), same as popeVersion above) - only readable from a real packaged
     * jar, same reason this belongs here rather than PopePluginFunctionalTest.
     */
    @Test
    fun `popeInstall warns, but still succeeds, when a resolved dependency's major popeToolVersion differs`() {
        val pluginVersion =
            System.getProperty("popePluginVersion")
                ?: error("popePluginVersion system property not set - see build.gradle.kts's functionalTest task")

        val registryDir = createTempDirectory("pope-version-mismatch-registry").toFile()
        val greeterDir = File(registryDir, "example.greeter")
        File("src/functionalTest/resources/fixtures/greeter-package").copyRecursively(greeterDir)
        // Deliberately a different major than whatever this build is currently at (e.g.
        // "0.1.0-SNAPSHOT") - "99" is never going to collide with a real running version.
        JSONObject(File(greeterDir, "openedge-project.json").readText())
            .put("popeToolVersion", "99.0.0")
            .let { File(greeterDir, "openedge-project.json").writeText(it.toString(2)) }
        val registryPath = registryDir.absolutePath.replace("\\", "/")

        val projectDir = createTempDirectory("pope-version-mismatch-consumer").toFile()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
            }
            rootProject.name = "version-mismatch-fixture"
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
                .put("name", "version-mismatch-fixture")
                .put("version", "1.0.0")
                .put("popePackageName", "example.versionmismatch")
                .put("popeDependencies", JSONObject().put("example.greeter", "^1.0.0"))
                .put(
                    "buildPath",
                    org.json.JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                ).toString(2),
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("popeInstall")
                .build()

        assertTrue(
            result.output.contains("\"example.greeter\" was written by pope 99.0.0"),
            "Expected a major-version-mismatch warning naming example.greeter and 99.0.0, got:\n${result.output}",
        )
        assertTrue(
            result.output.contains("BUILD SUCCESSFUL"),
            "Expected the install to still succeed despite the warning, got:\n${result.output}",
        )
    }

    /**
     * popeStamp exists specifically so a package with no Gradle wiring of its own (a plain
     * manifest+source repo, like a leaf dependency) can still get popeToolVersion refreshed -
     * pope.projectRoot points at that package's checkout from an entirely separate Gradle project,
     * and nothing but its openedge-project.json should ever be touched.
     */
    @Test
    fun `popeStamp refreshes popeToolVersion on an external projectRoot, touching nothing else there`() {
        val pluginVersion =
            System.getProperty("popePluginVersion")
                ?: error("popePluginVersion system property not set - see build.gradle.kts's functionalTest task")

        // The "package" being stamped - no gradlew, no settings.gradle.kts, nothing but its own
        // manifest+source, exactly like a real leaf dependency repo.
        val packageDir = createTempDirectory("pope-stamp-target-package").toFile()
        packageDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "stamp-target-package")
                .put("version", "1.0.0")
                .put("popePackageName", "example.stamptarget")
                .put("popeDependencies", JSONObject())
                .put(
                    "buildPath",
                    org.json.JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                ).toString(2),
        )
        val packagePath = packageDir.absolutePath.replace("\\", "/")

        // The separate "stamper" project - this is where all the Gradle wiring lives, never inside
        // packageDir.
        val stamperDir = createTempDirectory("pope-stamp-stamper").toFile()
        stamperDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
            }
            rootProject.name = "stamper-fixture"
            """.trimIndent(),
        )
        stamperDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope") version "$pluginVersion"
            }

            pope {
                projectRoot.set(file("$packagePath"))
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner.create()
                .withProjectDir(stamperDir)
                .withArguments("popeStamp")
                .build()

        assertTrue(
            result.output.contains("refreshed popeToolVersion"),
            "Expected popeStamp to report refreshing popeToolVersion, got:\n${result.output}",
        )
        val manifest = JSONObject(File(packageDir, "openedge-project.json").readText())
        assertTrue(
            manifest.getString("popeToolVersion") == pluginVersion,
            "Expected popeToolVersion to be stamped as \"$pluginVersion\", got: ${manifest.opt("popeToolVersion")}",
        )
        assertTrue(
            packageDir.listFiles()!!.map { it.name }.toSet() == setOf("openedge-project.json"),
            "Expected nothing but openedge-project.json in packageDir, got: ${packageDir.listFiles()!!.map { it.name }}",
        )
    }
}
