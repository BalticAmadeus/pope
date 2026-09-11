package pope.manifest

import java.io.File

/**
 * Infers package_name from .cls files' declared namespace (e.g. "class
 * example.closer.Closer:" implies "example.closer"). Every file must
 * agree on one namespace (ADR-0002) - fails loudly instead of guessing.
 */
object PackageNameInferrer {
    private val classDeclaration = Regex("""(?im)^\s*class\s+([A-Za-z_][\w.]*)\s*:""")

    fun infer(sourceDir: File): String {
        val namespaces =
            sourceDir
                .walkTopDown()
                .filter { it.isFile && it.extension.equals("cls", ignoreCase = true) }
                .mapNotNull { file -> classDeclaration.find(file.readText())?.groupValues?.get(1) }
                .map { qualifiedClassName -> qualifiedClassName.substringBeforeLast('.') }
                .toSet()

        return when (namespaces.size) {
            0 ->
                throw IllegalStateException(
                    "Could not infer package_name: no .cls files with a recognizable " +
                        "\"class <namespace>.<Name>:\" declaration found under ${sourceDir.path}",
                )
            1 -> namespaces.single()
            else ->
                throw IllegalStateException(
                    "Could not infer package_name: .cls files under ${sourceDir.path} disagree on namespace " +
                        "(found ${namespaces.sorted()}) — set package_name explicitly in openedge-project.json instead",
                )
        }
    }
}
