package dev.klone.composite

import dev.klone.resolver.ModuleDetector
import dev.klone.resolver.ScriptParser
import java.io.File

/**
 * Gradle configures every project of an included build, not only the ones the host uses. Samples,
 * benchmarks and baseline-profile modules of a cloned library then cost configuration time on every
 * sync, and they break first when Klone aligns the library to the host's AGP.
 *
 * Projects the host does not need get a build file name that does not exist: Gradle configures them
 * as empty projects (public `ProjectDescriptor.buildFileName`). Kept: the selected modules, the
 * projects they depend on (`project(":x")`, `projects.x`) and their parents. Baseline-profile producers
 * (`baselineProfile(project(":baselineprofile"))`) are not kept: they only run when profiles are generated.
 */
internal object ModuleTrimmer {

    private const val START = "// <klone-modules>"
    private const val END = "// </klone-modules>"
    const val UNUSED_BUILD_FILE = "klone-unused.gradle"

    private val PROJECT_CALL = Regex("""(?<![\w.])project\s*\(\s*(?:path\s*[=:]\s*)?(["'][^"']*["']|[^)"']+)""")
    private val TYPESAFE = Regex("""(?<![\w.])projects((?:\.[A-Za-z_]\w*)+)""")
    // Producers of baseline profiles (androidx.baselineprofile) only run when profiles are generated
    private val BASELINE_PROFILE = Regex("""(?<![\w.])baselineProfile\s*\(\s*(?:project\s*\([^)]*\)|projects(?:\.\w+)+)\s*\)""")

    /** Writes (or removes, with an empty [keep] or `null`) the block. Returns the paths configured empty. */
    fun trim(localDir: File, keep: Collection<String>?): List<String> {
        val settings = listOf("settings.gradle.kts", "settings.gradle").map { File(localDir, it) }.firstOrNull { it.isFile }
            ?: return emptyList()
        val skipped = keep?.takeIf { it.isNotEmpty() }?.let { unused(localDir, it) }.orEmpty()
        val content = settings.readText()
        val updated = if (skipped.isEmpty()) {
            HostPropagation.removeBlock(content, START, END)
        } else {
            HostPropagation.upsertBlock(content, START, END, block(skipped, settings.name.endsWith(".kts")))
        }
        if (updated != content) settings.writeText(updated)
        return skipped
    }

    /** Projects outside the dependency closure of [keep]; empty when the closure cannot be known statically. */
    internal fun unused(localDir: File, keep: Collection<String>): List<String> {
        val projects = ModuleDetector.allProjects(localDir)
        if (projects.isEmpty()) return emptyList()
        val byAccessor = projects.keys.associateBy { path -> path.removePrefix(":").split(':').joinToString(".") { accessorKey(it) } }

        val needed = mutableSetOf<String>()
        val queue = ArrayDeque(keep.filter { it in projects })
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!needed.add(path)) continue
            val script = listOf("build.gradle.kts", "build.gradle").map { File(projects.getValue(path), it) }.firstOrNull { it.isFile }
                ?.let { BASELINE_PROFILE.replace(ScriptParser.stripComments(it.readText()), "") } ?: continue
            for (match in PROJECT_CALL.findAll(script)) {
                // project(someVariable): the closure is unknown, configure everything
                val target = ScriptParser.stringLiteral(match.groupValues[1].trim()) ?: return emptyList()
                val absolute = if (target.startsWith(":")) target else ":$target"
                if (absolute in projects) queue += absolute
            }
            for (match in TYPESAFE.findAll(script)) {
                val accessor = match.groupValues[1].removePrefix(".").split('.').joinToString(".") { accessorKey(it) }
                // Longest known prefix: projects.core.data.dependencyProject → :core:data
                val target = generateSequence(accessor) { it.substringBeforeLast('.', "").ifEmpty { null } }
                    .firstNotNullOfOrNull { byAccessor[it] } ?: return emptyList()
                queue += target
            }
        }
        // Parents of kept projects stay (":a" for ":a:b")
        val withParents = needed.flatMap { path ->
            val segments = path.removePrefix(":").split(':')
            (1..segments.size).map { ":" + segments.take(it).joinToString(":") }
        }.toSet()
        return projects.keys.filter { it !in withParents }.sorted()
    }

    /** `landscapist-core` and `landscapistCore` (its type-safe accessor) compare equal. */
    private fun accessorKey(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private fun block(skipped: List<String>, kotlinDsl: Boolean): String {
        val quote = if (kotlinDsl) "\"" else "'"
        val list = skipped.joinToString(", ") { "$quote$it$quote" }
        val body = if (kotlinDsl) {
            "listOf($list).forEach { settings.findProject(it)?.buildFileName = \"$UNUSED_BUILD_FILE\" }"
        } else {
            "[$list].each { settings.findProject(it)?.buildFileName = '$UNUSED_BUILD_FILE' }"
        }
        return "$START\n// Klone: modules the host does not use are configured empty (no build script). " +
            "Configure them all with -Pklone.allModules=true\n$body\n$END"
    }
}
