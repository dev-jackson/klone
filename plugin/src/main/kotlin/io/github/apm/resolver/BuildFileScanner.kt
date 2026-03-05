package dev.klone.resolver

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import java.io.File

/**
 * Scans build.gradle.kts files for gitImplementation() calls BEFORE Gradle
 * evaluates them, so the settings plugin can clone repos and set up composite
 * builds in the settings phase.
 *
 * Only literal string patterns are supported (variables not supported by design).
 *
 * Supported patterns:
 *   gitImplementation("url", from = "1.0.0")
 *   gitImplementation("url", branch = "main")
 *   gitImplementation("url", commit = "abc123")
 *   gitImplementation("url", module = "converter-gson", from = "1.0.0")
 *   gitImplementation("url", modules = listOf("converter-gson", "adapter-rxjava2"), from = "1.0.0")
 */
object BuildFileScanner {

    // Matches calls that contain modules = listOf(...) — handles the nested paren
    private val MODULES_CALL_PATTERN = Regex(
        """gitImplementation\s*\(\s*["']([^"']+)["']([^(]*)\bmodules\s*=\s*listOf\s*\(([^)]*)\)([^)]*)\)"""
    )

    // Matches simple calls with no nested parens (covers from/branch/commit/module params)
    private val SIMPLE_CALL_PATTERN = Regex(
        """gitImplementation\s*\(\s*["']([^"']+)["']([^)(]*)\)"""
    )

    private val FROM_PARAM = Regex("""(?<!\w)from\s*=\s*["']([^"']+)["']""")
    private val BRANCH_PARAM = Regex("""(?<!\w)branch\s*=\s*["']([^"']+)["']""")
    private val COMMIT_PARAM = Regex("""(?<!\w)commit\s*=\s*["']([^"']+)["']""")
    private val MODULE_PARAM = Regex("""(?<!\w)module\s*=\s*["']([^"']+)["']""")
    private val STRING_LITERAL = Regex("""["']([^"']+)["']""")

    fun scanProject(projectDir: File): List<GitDependency> {
        return projectDir.walkTopDown()
            .filter { it.name == "build.gradle.kts" || it.name == "build.gradle" }
            .filter { !it.absolutePath.contains("${File.separator}build${File.separator}") }
            .flatMap { scanFile(it) }
            .distinctBy { it.url + it.ref.toString() + it.submoduleNames?.joinToString(",") }
            .toList()
    }

    fun scanFile(buildFile: File): List<GitDependency> {
        if (!buildFile.exists()) return emptyList()
        val content = buildFile.readText()
        val deps = mutableListOf<GitDependency>()

        // First pass: calls with modules = listOf(...)
        MODULES_CALL_PATTERN.findAll(content).forEach { match ->
            val url = match.groupValues[1]
            val paramsBefore = match.groupValues[2]
            val listContent = match.groupValues[3]
            val paramsAfter = match.groupValues[4]

            val allParams = paramsBefore + paramsAfter
            val ref = extractRef(allParams) ?: return@forEach
            val moduleNames = STRING_LITERAL.findAll(listContent).map { it.groupValues[1] }.toList()

            deps += GitDependency(url = url, ref = ref, submoduleNames = moduleNames)
        }

        // Second pass: simple calls (no nested parens — won't match listOf calls)
        SIMPLE_CALL_PATTERN.findAll(content).forEach { match ->
            val url = match.groupValues[1]
            val params = match.groupValues[2]

            val ref = extractRef(params) ?: return@forEach
            val moduleName = MODULE_PARAM.find(params)?.groupValues?.get(1)
            val submoduleNames = if (moduleName != null) listOf(moduleName) else null

            deps += GitDependency(url = url, ref = ref, submoduleNames = submoduleNames)
        }

        return deps.distinctBy { it.url + it.ref.toString() + it.submoduleNames?.joinToString(",") }
    }

    private fun extractRef(params: String): GitRef? {
        FROM_PARAM.find(params)?.let { return GitRef.Tag(it.groupValues[1]) }
        BRANCH_PARAM.find(params)?.let { return GitRef.Branch(it.groupValues[1]) }
        COMMIT_PARAM.find(params)?.let { return GitRef.Commit(it.groupValues[1]) }
        return null
    }
}
