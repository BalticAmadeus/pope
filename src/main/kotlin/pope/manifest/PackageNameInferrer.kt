package pope.manifest

import java.io.File

/**
 * Infers package_name from .cls files' declared namespace (e.g. "class
 * example.closer.Closer:" implies "example.closer"). Matches both CLASS
 * and INTERFACE, and allows modifiers (IMPLEMENTS/ABSTRACT/INHERITS/...)
 * between the name and the colon - real ABL commonly has both. Every
 * file must agree on one namespace (ADR-0002) - fails loudly instead of
 * guessing.
 */
object PackageNameInferrer {
    private val classDeclaration = Regex("""(?im)^\s*(?:class|interface)\s+([A-Za-z_][\w.]*)""")

    fun infer(sourceDir: File): String {
        val namespaces = namespacesOf(sourceDir)

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

    /**
     * Fails loudly if any .cls file's namespace isn't declaredPackageName itself or nested under
     * it (e.g. "ba.util.Formatter.X" is fine when declaredPackageName is "ba.util") - unlike
     * infer(), which has nothing to check against yet, this validates a name that's already
     * declared, so sub-namespaces under it are expected, not a disagreement.
     */
    fun validateAgainstDeclared(sourceDir: File, declaredPackageName: String) {
        val namespaces = namespacesOf(sourceDir)
        check(namespaces.isNotEmpty()) {
            "Could not find any .cls files with a recognizable class/interface declaration under " +
                "${sourceDir.path} to check popePackageName (\"$declaredPackageName\") against."
        }

        val mismatches = namespaces.filterNot { it == declaredPackageName || it.startsWith("$declaredPackageName.") }
        check(mismatches.isEmpty()) {
            "popePackageName (\"$declaredPackageName\") disagrees with the real namespace found in .cls files " +
                "(found ${mismatches.sorted()}) - fix whichever one is wrong before publishing."
        }
    }

    private fun namespacesOf(sourceDir: File): Set<String> =
        sourceDir
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("cls", ignoreCase = true) }
            .mapNotNull { file -> classDeclaration.find(file.readText())?.groupValues?.get(1) }
            .map { qualifiedClassName -> qualifiedClassName.substringBeforeLast('.') }
            .toSet()
}
