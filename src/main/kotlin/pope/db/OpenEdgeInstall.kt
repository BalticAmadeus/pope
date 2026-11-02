package pope.db

import java.io.File

/**
 * An OpenEdge installation, found through the DLC directory (the same variable OpenEdge's own
 * tools use). Only the pieces needed to build a schema database from a .df.
 */
class OpenEdgeInstall(val dlc: File, windows: Boolean = System.getProperty("os.name").startsWith("Windows")) {
    val procopy: File = File(dlc, if (windows) "bin/procopy.bat" else "bin/procopy")
    val progres: File = File(dlc, if (windows) "bin/_progres.exe" else "bin/_progres")

    /** OpenEdge's own empty database, copied with procopy as the starting point of every schema database. */
    val emptyDatabase: File = File(dlc, "empty")

    /** What the OpenEdge tools expect to find in their environment. */
    val environment: Map<String, String> = mapOf("DLC" to dlc.path)

    companion object {
        fun locate(explicitDlc: String?, environment: Map<String, String> = System.getenv()): OpenEdgeInstall {
            val dlc =
                explicitDlc?.takeIf { it.isNotBlank() }
                    ?: environment["DLC"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "OpenEdge not found: set the DLC environment variable, or pass -PpopeDlc=<OpenEdge install dir>",
                    )

            val dlcDir = File(dlc)
            check(File(dlcDir, "bin").isDirectory) {
                "OpenEdge not found at \"$dlc\" (no bin folder inside) - check DLC or -PpopeDlc"
            }
            return OpenEdgeInstall(dlcDir)
        }
    }
}
