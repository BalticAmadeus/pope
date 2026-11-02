package pope.db

import java.io.File

/** A built, empty, schema-only database: enough for the ABL compiler, holding no data. */
class SchemaDatabase(val directory: File, val name: String) {
    val databaseFile: File = File(directory, "$name.db")

    /**
     * Connection parameters for compiling against it. Single-user (-1) because nothing needs a database
     * server running, and read-only (-RO) because the schema is only ever read once it is loaded.
     */
    fun connectArguments(): List<String> = listOf("-db", databaseFile.path.removeSuffix(".db").replace('\\', '/'), "-1", "-RO")
}

/**
 * Builds a schema-only OpenEdge database from .df files: copy OpenEdge's empty database, then load each
 * .df into it. The database has to be writable while the schema loads, so the read-only connection is
 * only for whoever compiles against it afterwards.
 */
class SchemaDatabaseBuilder(private val install: OpenEdgeInstall, private val runner: CommandRunner) {
    fun build(outputDir: File, name: String, schemaFiles: List<File>): SchemaDatabase {
        require(schemaFiles.isNotEmpty()) { "No schema (.df) files given to build \"$name\" from" }
        schemaFiles.forEach { require(it.isFile) { "Schema file not found: ${it.path}" } }
        failOnTableDefinedTwice(schemaFiles)

        outputDir.mkdirs()
        removePreviousBuild(outputDir, name)

        val copy =
            runner.run(
                listOf(install.procopy.path, install.emptyDatabase.path, File(outputDir, name).path),
                outputDir,
                install.environment,
            )
        check(copy.exitCode == 0 && File(outputDir, "$name.db").exists()) {
            "Could not create an empty database for \"$name\" (procopy exit ${copy.exitCode}):\n${copy.output}"
        }

        File(outputDir, SCHEMA_LIST).writeText(schemaFiles.joinToString("\n") { it.absolutePath.replace('\\', '/') })
        File(outputDir, LOAD_PROGRAM).writeText(LOAD_PROGRAM_SOURCE)

        val load =
            runner.run(
                listOf(install.progres.path, "-b", "-db", name, "-1", "-p", LOAD_PROGRAM),
                outputDir,
                install.environment,
            )
        check(load.exitCode == 0) { "Loading the schema into \"$name\" failed (exit ${load.exitCode}):\n${load.output}" }
        check(File(outputDir, LOAD_OK).exists()) {
            val reason = File(outputDir, LOAD_ERROR).takeIf { it.exists() }?.readText()?.trim()
            "Loading the schema into \"$name\" did not finish" + (reason?.let { ": $it" } ?: ".") +
                " See ${File(outputDir, "$name.lg").path}"
        }

        return SchemaDatabase(outputDir, name)
    }

    /**
     * OpenEdge silently ignores a second definition of a table that already exists: it exits 0 and keeps
     * the first one (checked by hand with OpenEdge 12.8). So two .df files defining the same table would
     * quietly give a database that doesn't match the second one - fail before loading instead.
     */
    private fun failOnTableDefinedTwice(schemaFiles: List<File>) {
        val definedIn = HashMap<String, File>()
        for (file in schemaFiles) {
            for (table in TABLE_DEFINITION.findAll(file.readText()).map { it.groupValues[1] }) {
                val previous = definedIn.putIfAbsent(table.lowercase(), file)
                check(previous == null) {
                    "Table \"$table\" is defined in both ${previous!!.name} and ${file.name} - a table can only come " +
                        "from one schema file, because OpenEdge would silently keep the first definition."
                }
            }
        }
    }

    /** procopy asks before overwriting, which would hang a non-interactive run - so start from nothing. */
    private fun removePreviousBuild(outputDir: File, name: String) {
        outputDir.listFiles { file -> file.isFile && file.name.startsWith("$name.") }.orEmpty().forEach { it.delete() }
        listOf(LOAD_OK, LOAD_ERROR).forEach { File(outputDir, it).delete() }
    }

    private companion object {
        const val SCHEMA_LIST = "schema-files.txt"
        const val LOAD_PROGRAM = "load-schema.p"
        const val LOAD_OK = "load-ok.txt"
        const val LOAD_ERROR = "load-error.txt"

        val TABLE_DEFINITION = Regex("""(?im)^\s*ADD\s+TABLE\s+"([^"]+)"""")

        val LOAD_PROGRAM_SOURCE =
            """
            DEFINE VARIABLE dfFile AS CHARACTER NO-UNDO.
            INPUT FROM VALUE("$SCHEMA_LIST").
            REPEAT:
                IMPORT UNFORMATTED dfFile.
                IF dfFile = "" THEN NEXT.
                RUN prodict/load_df.p (dfFile) NO-ERROR.
                IF ERROR-STATUS:ERROR THEN DO:
                    OUTPUT TO VALUE("$LOAD_ERROR").
                    PUT UNFORMATTED dfFile ": " ERROR-STATUS:GET-MESSAGE(1) SKIP.
                    OUTPUT CLOSE.
                    QUIT.
                END.
            END.
            INPUT CLOSE.
            OUTPUT TO VALUE("$LOAD_OK").
            PUT UNFORMATTED "ok" SKIP.
            OUTPUT CLOSE.
            QUIT.
            """.trimIndent()
    }
}
