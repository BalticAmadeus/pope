package pope.db

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaDatabaseBuilderTest {
    private val install = OpenEdgeInstall(File("/oe"), windows = false)

    /** Stands in for OpenEdge: records every command, and can mimic what the real tools leave behind. */
    private class FakeOpenEdge(
        private val procopyExit: Int = 0,
        private val loadExit: Int = 0,
        private val loadWritesOk: Boolean = true,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()
        var databasePresentAtProcopy: Boolean? = null

        override fun run(command: List<String>, workingDir: File, environment: Map<String, String>): CommandResult {
            commands += command
            if (command.first().endsWith("procopy")) {
                val target = File(command[2])
                databasePresentAtProcopy = File(target.path + ".db").exists()
                if (procopyExit == 0) File(target.path + ".db").writeText("db")
                return CommandResult(procopyExit, "procopy output")
            }
            if (loadExit == 0 && loadWritesOk) File(workingDir, "load-ok.txt").writeText("ok")
            return CommandResult(loadExit, "progres output")
        }
    }

    private fun schemaFile(dir: File, fileName: String, vararg tables: String): File =
        File(dir, fileName).apply {
            writeText(tables.joinToString("\n\n") { "ADD TABLE \"$it\"\n  AREA \"Schema Area\"" })
        }

    @Test
    fun `copies the empty database, then loads every schema file in order`() {
        val dir = createTempDirectory("pope-db-test").toFile()
        val customer = schemaFile(dir, "customer.df", "Customer")
        val orders = schemaFile(dir, "orders.df", "Orders")
        val fake = FakeOpenEdge()

        val database = SchemaDatabaseBuilder(install, fake).build(File(dir, "out"), "sportsdb", listOf(customer, orders))

        assertEquals(2, fake.commands.size)
        assertEquals(listOf(install.procopy.path, install.emptyDatabase.path, File(dir, "out/sportsdb").path), fake.commands[0])
        assertEquals(listOf(install.progres.path, "-b", "-db", "sportsdb", "-1", "-p", "load-schema.p"), fake.commands[1])
        assertEquals(
            listOf(customer, orders).map { it.absolutePath.replace('\\', '/') },
            File(dir, "out/schema-files.txt").readLines(),
        )
        assertEquals("sportsdb", database.name)
        assertTrue(File(dir, "out/sportsdb.db").exists())
    }

    @Test
    fun `the connect arguments are single-user and read-only`() {
        val database = SchemaDatabase(File("/work/out"), "sportsdb")

        val arguments = database.connectArguments()

        assertEquals(listOf("-db", "/work/out/sportsdb", "-1", "-RO"), arguments.map { it.replace("C:", "") })
    }

    @Test
    fun `a table defined in two schema files fails before OpenEdge is run`() {
        val dir = createTempDirectory("pope-db-test").toFile()
        val first = schemaFile(dir, "first.df", "Customer")
        val second = schemaFile(dir, "second.df", "customer")
        val fake = FakeOpenEdge()

        val error =
            assertFailsWith<IllegalStateException> {
                SchemaDatabaseBuilder(install, fake).build(File(dir, "out"), "db", listOf(first, second))
            }

        assertTrue(error.message!!.contains("first.df") && error.message!!.contains("second.df"), error.message)
        assertTrue(fake.commands.isEmpty(), "Expected no OpenEdge command to run, got ${fake.commands}")
    }

    @Test
    fun `a missing schema file is reported by name`() {
        val dir = createTempDirectory("pope-db-test").toFile()

        val error =
            assertFailsWith<IllegalArgumentException> {
                SchemaDatabaseBuilder(install, FakeOpenEdge()).build(File(dir, "out"), "db", listOf(File(dir, "nope.df")))
            }

        assertTrue(error.message!!.contains("nope.df"), error.message)
    }

    @Test
    fun `no schema files at all is rejected`() {
        val dir = createTempDirectory("pope-db-test").toFile()

        assertFailsWith<IllegalArgumentException> {
            SchemaDatabaseBuilder(install, FakeOpenEdge()).build(File(dir, "out"), "db", emptyList())
        }
    }

    @Test
    fun `a failing procopy is reported with its output`() {
        val dir = createTempDirectory("pope-db-test").toFile()
        val customer = schemaFile(dir, "customer.df", "Customer")

        val error =
            assertFailsWith<IllegalStateException> {
                SchemaDatabaseBuilder(install, FakeOpenEdge(procopyExit = 1)).build(File(dir, "out"), "db", listOf(customer))
            }

        assertTrue(error.message!!.contains("procopy output"), error.message)
    }

    @Test
    fun `a failing schema load is reported with its output`() {
        val dir = createTempDirectory("pope-db-test").toFile()
        val customer = schemaFile(dir, "customer.df", "Customer")

        val error =
            assertFailsWith<IllegalStateException> {
                SchemaDatabaseBuilder(install, FakeOpenEdge(loadExit = 2)).build(File(dir, "out"), "db", listOf(customer))
            }

        assertTrue(error.message!!.contains("progres output"), error.message)
    }

    @Test
    fun `a load that exits cleanly but never reaches the end is still a failure`() {
        val dir = createTempDirectory("pope-db-test").toFile()
        val customer = schemaFile(dir, "customer.df", "Customer")

        val error =
            assertFailsWith<IllegalStateException> {
                SchemaDatabaseBuilder(install, FakeOpenEdge(loadWritesOk = false)).build(File(dir, "out"), "db", listOf(customer))
            }

        assertTrue(error.message!!.contains("did not finish"), error.message)
    }

    @Test
    fun `a previous build of the same database is cleared first, so procopy never has to ask about overwriting`() {
        val dir = createTempDirectory("pope-db-test").toFile()
        val customer = schemaFile(dir, "customer.df", "Customer")
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "db.db").writeText("old")
        File(out, "db.lg").writeText("old log")
        File(out, "load-ok.txt").writeText("stale marker")
        val fake = FakeOpenEdge(loadWritesOk = false)

        assertFailsWith<IllegalStateException> {
            SchemaDatabaseBuilder(install, fake).build(out, "db", listOf(customer))
        }

        assertEquals(false, fake.databasePresentAtProcopy)
        assertTrue(!File(out, "db.lg").exists())
        assertTrue(!File(out, "load-ok.txt").exists(), "Expected a stale success marker not to make a failed load look fine")
    }
}

class OpenEdgeInstallTest {
    @Test
    fun `an explicit location wins over the DLC variable`() {
        val explicit = createTempDirectory("pope-oe-explicit").toFile().also { File(it, "bin").mkdirs() }
        val fromEnvironment = createTempDirectory("pope-oe-env").toFile().also { File(it, "bin").mkdirs() }

        val install = OpenEdgeInstall.locate(explicit.path, mapOf("DLC" to fromEnvironment.path))

        assertEquals(explicit, install.dlc)
    }

    @Test
    fun `the DLC variable is used when nothing explicit is given`() {
        val dlc = createTempDirectory("pope-oe-env").toFile().also { File(it, "bin").mkdirs() }

        assertEquals(dlc, OpenEdgeInstall.locate(null, mapOf("DLC" to dlc.path)).dlc)
    }

    @Test
    fun `neither an explicit location nor DLC gives a message that says how to fix it`() {
        val error = assertFailsWith<IllegalStateException> { OpenEdgeInstall.locate(null, emptyMap()) }

        assertTrue(error.message!!.contains("DLC") && error.message!!.contains("-PpopeDlc"), error.message)
    }

    @Test
    fun `a folder that is not an OpenEdge install is rejected`() {
        val notOpenEdge = createTempDirectory("pope-not-oe").toFile()

        val error = assertFailsWith<IllegalStateException> { OpenEdgeInstall.locate(notOpenEdge.path, emptyMap()) }

        assertTrue(error.message!!.contains(notOpenEdge.path), error.message)
    }
}
