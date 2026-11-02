package pope

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs popeDbBuild against a real OpenEdge install, then compiles a database-bound class against the
 * result. Skipped when DLC isn't set (CI runners have no licensed OpenEdge), so this only ever runs on a
 * developer machine with OpenEdge.
 */
class PopeDbBuildFunctionalTest {
    private val dlc: String? = System.getenv("DLC")

    private fun tableDefinition(table: String, vararg fields: Pair<String, String>): String {
        val builder = StringBuilder("ADD TABLE \"$table\"\r\n  AREA \"Schema Area\"\r\n  DUMP-NAME \"${table.lowercase()}\"\r\n\r\n")
        fields.forEachIndexed { index, (name, type) ->
            val format = if (type == "integer") ">>>>9" else "x(30)"
            builder.append("ADD FIELD \"$name\" OF \"$table\" AS $type\r\n  FORMAT \"$format\"\r\n  INITIAL \"\"\r\n")
            builder.append("  POSITION ${index + 2}\r\n  ORDER ${(index + 1) * 10}\r\n\r\n")
        }
        builder.append("ADD INDEX \"${fields.first().first}\" ON \"$table\"\r\n  AREA \"Schema Area\"\r\n  UNIQUE\r\n  PRIMARY\r\n")
        builder.append("  INDEX-FIELD \"${fields.first().first}\" ASCENDING\r\n\r\n.\r\nPSC\r\ncpstream=UTF-8\r\n.\r\n0000000000\r\n")
        return builder.toString()
    }

    private fun projectWithSchemas(): File {
        val projectDir = createTempDirectory("pope-db-functional").toFile()
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "db-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.balticamadeus.pope")
            }
            """.trimIndent(),
        )
        projectDir.resolve("customer.df").writeText(tableDefinition("Customer", "CustNum" to "integer", "Name" to "character"))
        projectDir.resolve("orders.df").writeText(tableDefinition("Orders", "OrderNum" to "integer", "CustNum" to "integer"))
        return projectDir
    }

    @Test
    fun `popeDbBuild makes a database that a class using two schema files compiles against`() {
        assumeTrue(dlc != null, "DLC is not set - no OpenEdge install to test against")
        val projectDir = projectWithSchemas()

        val build =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeDbBuild", "-PpopeDbName=sportsdb", "-PpopeDbSchema=customer.df,orders.df")
                .build()

        assertTrue(File(projectDir, "pope_db/sportsdb/sportsdb.db").exists(), "Expected the database to be built")
        val connectLine = build.output.lines().first { it.startsWith("Connect with: ") }
        val connectArguments = connectLine.removePrefix("Connect with: ").split(" ")
        assertTrue(connectArguments.containsAll(listOf("-1", "-RO")), "Expected a single-user read-only connection, got: $connectLine")

        File(projectDir, "src/demo").mkdirs()
        File(projectDir, "src/demo/Both.cls").writeText(
            """
            CLASS demo.Both:
                METHOD PUBLIC INTEGER OrderCount(custNum AS INTEGER):
                    DEFINE VARIABLE n AS INTEGER NO-UNDO.
                    FIND Customer WHERE Customer.CustNum = custNum NO-LOCK NO-ERROR.
                    FOR EACH Orders WHERE Orders.CustNum = custNum NO-LOCK:
                        n = n + 1.
                    END.
                    RETURN n.
                END METHOD.
            END CLASS.
            """.trimIndent(),
        )
        File(projectDir, "rcode").mkdirs()
        File(projectDir, "compile.p").writeText(
            """
            PROPATH = "src," + PROPATH.
            OUTPUT TO VALUE("compile-result.txt").
            COMPILE VALUE("demo/Both.cls") SAVE INTO VALUE("rcode") NO-ERROR.
            IF COMPILER:ERROR THEN PUT UNFORMATTED "FAILED" SKIP. ELSE PUT UNFORMATTED "OK" SKIP.
            OUTPUT CLOSE.
            QUIT.
            """.trimIndent(),
        )

        val progres = File(dlc!!, if (System.getProperty("os.name").startsWith("Windows")) "bin/_progres.exe" else "bin/_progres")
        val compile =
            ProcessBuilder(listOf(progres.path, "-b") + connectArguments + listOf("-p", "compile.p"))
                .directory(projectDir)
                .redirectErrorStream(true)
                .also { it.environment()["DLC"] = dlc }
                .start()
        val compileOutput = compile.inputStream.bufferedReader().readText()
        compile.waitFor()

        assertTrue(
            File(projectDir, "compile-result.txt").readText().trim() == "OK",
            "Expected the class to compile against the built database, got:\n$compileOutput",
        )
        assertTrue(File(projectDir, "rcode/demo/Both.r").exists(), "Expected r-code to be written")
    }

    @Test
    fun `popeDbBuild fails with a clear message when the same table is in two schema files`() {
        assumeTrue(dlc != null, "DLC is not set - no OpenEdge install to test against")
        val projectDir = projectWithSchemas()
        projectDir.resolve("customer-again.df").writeText(tableDefinition("Customer", "CustNum" to "character"))

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("popeDbBuild", "-PpopeDbName=sportsdb", "-PpopeDbSchema=customer.df,customer-again.df")
                .buildAndFail()

        assertTrue(
            result.output.contains("customer.df") && result.output.contains("customer-again.df"),
            "Expected both schema files named, got:\n${result.output}",
        )
    }

    @Test
    fun `popeDbBuild without OpenEdge fails telling the user to set DLC`() {
        val projectDir = projectWithSchemas()

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withEnvironment(System.getenv().filterKeys { !it.equals("DLC", ignoreCase = true) })
                .withArguments("popeDbBuild", "-PpopeDbName=sportsdb", "-PpopeDbSchema=customer.df")
                .buildAndFail()

        assertTrue(result.output.contains("DLC"), "Expected a message about DLC, got:\n${result.output}")
    }
}
