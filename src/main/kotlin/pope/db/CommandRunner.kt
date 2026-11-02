package pope.db

import java.io.File
import java.io.IOException

data class CommandResult(val exitCode: Int, val output: String)

/** Runs an external program. An interface so tests can fake OpenEdge, which CI doesn't have. */
fun interface CommandRunner {
    fun run(command: List<String>, workingDir: File, environment: Map<String, String>): CommandResult
}

object ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, workingDir: File, environment: Map<String, String>): CommandResult {
        val builder = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true)
        builder.environment().putAll(environment)

        val process =
            try {
                builder.start()
            } catch (e: IOException) {
                throw IllegalStateException("Could not run \"${command.first()}\": ${e.message}", e)
            }

        val output = process.inputStream.bufferedReader().readText()
        return CommandResult(process.waitFor(), output)
    }
}
