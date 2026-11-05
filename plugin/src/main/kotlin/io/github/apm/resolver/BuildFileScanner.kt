package dev.klone.resolver

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.LocalDependency
import dev.klone.toml.VersionCatalogReader
import java.io.File

/**
 * Scans build.gradle(.kts) files for gitImplementation() / localImplementation() calls BEFORE
 * Gradle evaluates them, so the settings plugin can clone repos and set up composite builds in
 * the settings phase.
 *
 * Arguments must be string literals, `listOf("...")`, or version catalog references
 * (`libs.versions.retrofit.get()`). Calls may span lines; commented-out calls are ignored.
 *
 *   gitImplementation("url", from = "1.0.0")          // SPM: up to next major
 *   gitImplementation("url", exact = "1.0.0")
 *   gitImplementation("url", upToNextMinor = "1.4.0")
 *   gitImplementation("url", version = ">=1.0.0 <2.0.0")
 *   gitImplementation("url", tag = "release-7")
 *   gitImplementation("url", branch = "main")
 *   gitImplementation("url", commit = "abc123")
 *   gitImplementation("url", module = "converter-gson", from = libs.versions.retrofit.get())
 *   gitImplementation("url", modules = listOf("converter-gson", "adapter-rxjava2"))
 *   localImplementation("../shared", module = "core")
 */
object BuildFileScanner {

    private const val GIT_CALL = "gitImplementation"
    private const val LOCAL_CALL = "localImplementation"

    // Same order as the gitImplementation() parameters
    private val GIT_POSITIONAL = listOf(
        "url", "from", "branch", "commit", "module", "modules",
        "exact", "upToNextMinor", "version", "tag", "tagPattern", "dir"
    )
    private val REF_PARAMS = listOf("from", "exact", "upToNextMinor", "version", "tag", "branch", "commit")
    private val LOCAL_POSITIONAL = listOf("path", "module", "modules")

    private val SKIPPED_DIRS = setOf("build", ".gradle", ".git", ".idea", "node_modules")

    fun scanProject(projectDir: File, versions: Map<String, String> = VersionCatalogReader.read(projectDir)): List<GitDependency> =
        buildFiles(projectDir).flatMap { scanFile(it, versions) }.distinct().toList()

    fun scanFile(buildFile: File, versions: Map<String, String> = emptyMap()): List<GitDependency> =
        calls(buildFile, GIT_CALL).mapNotNull { call ->
            val args = bind(call, GIT_POSITIONAL)
            val url = args["url"]?.let { ScriptParser.stringLiteral(it) } ?: return@mapNotNull null
            val refs = REF_PARAMS.associateWith { value(args[it], versions) }
            val ref = try {
                GitRef.fromParams(refs["from"], refs["exact"], refs["upToNextMinor"], refs["version"], refs["tag"], refs["branch"], refs["commit"])
            } catch (e: IllegalArgumentException) {
                throw org.gradle.api.GradleException("[Klone] gitImplementation(\"$url\") in ${buildFile.path}: ${e.message}")
            }
            GitDependency(
                url = url,
                ref = ref ?: GitRef.Default,
                submoduleNames = submodules(args),
                tagPattern = value(args["tagPattern"], versions),
                dir = value(args["dir"], versions)
            )
        }.distinct()

    fun scanProjectForLocal(projectDir: File): List<LocalDependency> =
        buildFiles(projectDir).flatMap { scanFileForLocal(it) }.distinct().toList()

    fun scanFileForLocal(buildFile: File): List<LocalDependency> =
        calls(buildFile, LOCAL_CALL).mapNotNull { call ->
            val args = bind(call, LOCAL_POSITIONAL)
            val path = args["path"]?.let { ScriptParser.stringLiteral(it) } ?: return@mapNotNull null
            LocalDependency(path = path, submoduleNames = submodules(args))
        }.distinct()

    /** Build files of this build only: skips outputs and nested builds with their own settings file. */
    private fun buildFiles(projectDir: File): Sequence<File> =
        projectDir.walkTopDown()
            .onEnter { dir ->
                dir == projectDir || (dir.name !in SKIPPED_DIRS &&
                    !File(dir, "settings.gradle.kts").exists() && !File(dir, "settings.gradle").exists())
            }
            .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }

    private fun calls(buildFile: File, name: String): List<ScriptParser.Call> {
        if (!buildFile.exists()) return emptyList()
        return ScriptParser.findCalls(ScriptParser.stripComments(buildFile.readText()), setOf(name))
    }

    /** Maps positional and named args to parameter names. */
    private fun bind(call: ScriptParser.Call, positional: List<String>): Map<String, String> {
        val bound = mutableMapOf<String, String>()
        call.args.forEachIndexed { i, arg ->
            val name = arg.name ?: positional.getOrNull(i) ?: return@forEachIndexed
            bound[name] = arg.expr
        }
        return bound
    }

    private fun value(expr: String?, versions: Map<String, String>): String? =
        expr?.let { ScriptParser.stringLiteral(it) ?: VersionCatalogReader.resolve(it, versions) }

    private fun submodules(args: Map<String, String>): List<String>? =
        args["module"]?.let { ScriptParser.stringLiteral(it) }?.let { listOf(it) }
            ?: args["modules"]?.let { ScriptParser.stringList(it) }
}
