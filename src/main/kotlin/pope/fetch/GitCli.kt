package pope.fetch

import java.io.File
import java.io.IOException

class GitCommandFailedException(command: List<String>, exitCode: Int, stderr: String) :
    RuntimeException(
        "git command failed (exit $exitCode): ${command.joinToString(" ")}" +
            if (stderr.isNotBlank()) "\n$stderr" else "",
    )

/** Thin wrapper around shelling out to the system `git` executable. */
object GitCli {
    fun run(workingDir: File?, vararg args: String): String {
        val command = listOf("git") + args
        val process =
            try {
                ProcessBuilder(command)
                    .apply { if (workingDir != null) directory(workingDir) }
                    .start()
            } catch (e: IOException) {
                throw IllegalStateException(
                    "Could not run \"git\" — is it installed and on PATH? (${e.message})",
                    e,
                )
            }

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw GitCommandFailedException(command, exitCode, stderr)
        }

        return stdout
    }
}
