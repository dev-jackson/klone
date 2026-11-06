package dev.klone.resolver

import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File
import java.util.Properties

/**
 * Static checks behind `kloneDoctor`. Measured on real builds: cloned repos whose build plugins (ids and
 * versions, own buildSrc/build-logic) are identical share one plugin classloader, while every distinct
 * setup costs ~50 MB of Metaspace and seconds of cold start. Standardizing builds is the main lever.
 */
internal object BuildDoctor {

    data class Report(
        val name: String,
        /** Plugins on the root classpath: `id:version` or `group:name:version`. */
        val plugins: Set<String>,
        /** `buildSrc` and builds included in pluginManagement (convention plugins): always a classloader of their own. */
        val ownBuildLogic: List<String>,
        /** `subprojects {}` / `allprojects {}` in the root build script. */
        val rootLogic: List<String>,
        val agp: String?,
        val kotlin: String?,
        val modulesUsed: Int,
        val modulesTotal: Int,
        val hygiene: List<String>
    ) {
        /** Repos with the same key can share one plugin classloader. */
        val classpathKey: String get() = if (ownBuildLogic.isNotEmpty()) "own:$name" else plugins.sorted().joinToString(",")
    }

    fun analyze(dir: File, name: String): Report {
        val catalog = Catalog(File(dir, "gradle/libs.versions.toml"))
        val settings = script(dir, "settings")
        val root = script(dir, "build")

        val plugins = sortedSetOf<String>()
        // plugins { alias(libs.plugins.x) apply false; id("y") version "z" }
        Regex("""alias\s*\(\s*libs\.plugins\.([\w.]+)\s*\)""").findAll(root + "\n" + settings)
            .forEach { m -> catalog.plugin(m.groupValues[1])?.let { plugins += it } }
        Regex("""id\s*\(?\s*["']([\w.\-]+)["']\s*\)?\s*version\s*\(?\s*["']([^"']+)["']""").findAll(root + "\n" + settings)
            .forEach { plugins += "${it.groupValues[1]}:${it.groupValues[2]}" }
        // buildscript { dependencies { classpath("g:a:v") / classpath(libs.x) } }
        Regex("""classpath\s*\(?\s*["']([^"']+)["']""").findAll(root).forEach { plugins += it.groupValues[1] }
        Regex("""classpath\s*\(?\s*libs\.([\w.]+)""").findAll(root).forEach { m -> catalog.library(m.groupValues[1])?.let { plugins += it } }

        val ownBuildLogic = buildList {
            if (File(dir, "buildSrc").isDirectory) add("buildSrc")
            val pluginManagement = Regex("""pluginManagement\s*\{""").find(settings)?.let { m ->
                ScriptParser.matchingParen(settings, m.range.last)?.let { settings.substring(m.range.last, it) }
            }.orEmpty()
            ScriptParser.findCalls(pluginManagement, setOf("includeBuild"))
                .mapNotNull { call -> call.args.firstOrNull()?.let { ScriptParser.stringLiteral(it.expr) } }
                .forEach { add(it) }
        }
        val rootLogic = listOf("subprojects", "allprojects").filter { Regex("""(?<![\w.])$it\s*\{""").containsMatchIn(root) }

        val versions = PluginVersions.detect(dir)
        val all = ModuleDetector.allProjects(dir)
        // The block is a comment-delimited section: read it from the raw file
        val rawSettings = listOf("settings.gradle.kts", "settings.gradle").map { File(dir, it) }.firstOrNull { it.isFile }?.readText().orEmpty()
        val unused = trimmedModules(rawSettings).toSet()
        val used = all.filterKeys { it !in unused }

        val hygiene = buildList {
            val kept = used.values.mapNotNull { moduleDir -> script(moduleDir, "build").takeIf { it.isNotBlank() } }
            if (kept.any { Regex("""kotlin\s*\(\s*["']kapt["']|["'](?:kotlin-kapt|org\.jetbrains\.kotlin\.kapt)["']|(?<![\w.])kapt\s*\(""").containsMatchIn(it) }) {
                add("kapt (KSP is several times faster)")
            }
            // Both generate binding classes from every layout on each clean build (dataBindingGenBaseClasses)
            if (kept.any { Regex("""dataBinding\s*(?:=\s*true|\{[^}]*enabled\s*=\s*true)""").containsMatchIn(it) }) {
                add("Data Binding")
            }
            if (kept.any { Regex("""viewBinding\s*(?:=\s*true|\{[^}]*enabled\s*=\s*true)""").containsMatchIn(it) }) {
                add("View Binding")
            }
            val props = Properties().apply { File(dir, "gradle.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
            if (props.getProperty("android.nonTransitiveRClass") == "false") add("android.nonTransitiveRClass=false")
        }
        return Report(name, plugins, ownBuildLogic, rootLogic, versions[PluginVersions.AGP], versions[PluginVersions.KOTLIN],
            used.size, all.size, hygiene)
    }

    /** Text report: one row per repo, the distinct plugin classpaths and what to fix first. */
    fun render(host: Report, repos: List<Report>, metaspace: Pair<Long, Long>?): String = buildString {
        val distinct = repos.groupBy { it.classpathKey }
        appendLine("Klone doctor — ${repos.size} cloned repo(s), ${distinct.size} distinct plugin classpath(s)")
        appendLine("Repos with identical build plugins share one classloader; each distinct one costs ~50 MB of Metaspace and cold-start time.")
        appendLine()
        val header = listOf("Repo", "AGP", "Kotlin", "Own build logic", "Root logic", "Modules", "Hygiene")
        val rows = repos.sortedBy { it.name.lowercase() }.map { r ->
            listOf(
                r.name,
                mark(r.agp, host.agp),
                mark(r.kotlin, host.kotlin),
                r.ownBuildLogic.joinToString().ifEmpty { "-" },
                r.rootLogic.joinToString().ifEmpty { "-" },
                "${r.modulesUsed}/${r.modulesTotal}",
                r.hygiene.joinToString().ifEmpty { "-" }
            )
        }
        val widths = header.indices.map { i -> (rows.map { it[i] } + header[i]).maxOf { it.length } }
        (listOf(header) + rows).forEach { row -> appendLine(row.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ").trimEnd()) }
        appendLine("(* differs from the host: AGP ${host.agp ?: "?"}, Kotlin ${host.kotlin ?: "?"})")

        val shared = distinct.values.filter { it.size > 1 }
        if (shared.isNotEmpty()) {
            appendLine()
            appendLine("Sharing a plugin classpath: " + shared.joinToString("; ") { group -> group.joinToString { it.name } })
        }
        metaspace?.let { (used, max) ->
            appendLine()
            appendLine("Gradle daemon Metaspace now: ${used / MB} MB" + (if (max > 0) " of ${max / MB} MB" else " (no limit)") +
                " — run kloneDoctor after a build to see what the build itself needs")
        }

        val suggestions = buildList {
            repos.filter { it.ownBuildLogic.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { r ->
                add("${r.size} repo(s) bring their own build logic (${r.joinToString { it.name }}): move it to one shared convention plugin.")
            }
            repos.filter { it.ownBuildLogic.isEmpty() }.groupBy { it.classpathKey }.takeIf { it.size > 1 }?.let { groups ->
                add("${groups.size} different plugin sets among repos without own build logic: use the same plugins and versions everywhere.")
            }
            repos.filter { it.agp != null && host.agp != null && it.agp != host.agp || it.kotlin != null && host.kotlin != null && it.kotlin != host.kotlin }
                .takeIf { it.isNotEmpty() }?.let { r -> add("Align AGP/Kotlin with the host in: ${r.joinToString { it.name }}.") }
            repos.filter { it.rootLogic.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { r ->
                add("Root-level ${r.flatMap { it.rootLogic }.distinct().joinToString(" / ")} blocks in ${r.joinToString { it.name }}: move them to convention plugins.")
            }
            repos.filter { it.hygiene.isNotEmpty() }.forEach { r -> add("${r.name}: ${r.hygiene.joinToString()} slows every clean build.") }
        }
        if (suggestions.isNotEmpty()) {
            appendLine()
            appendLine("Suggestions:")
            suggestions.forEach { appendLine("  - $it") }
        } else {
            appendLine()
            appendLine("No issues found: every cloned repo is standardized.")
        }
    }

    private const val MB = 1024L * 1024L

    private fun mark(value: String?, host: String?) = when {
        value == null -> "-"
        host != null && value != host -> "$value*"
        else -> value
    }

    private fun script(dir: File, base: String): String =
        listOf("$base.gradle.kts", "$base.gradle").map { File(dir, it) }.firstOrNull { it.isFile }
            ?.let { ScriptParser.stripComments(it.readText()) }.orEmpty()

    /** Paths Klone configures empty (its `// <klone-modules>` block). */
    private fun trimmedModules(settings: String): List<String> {
        val block = Regex("""<klone-modules>(.*?)</klone-modules>""", RegexOption.DOT_MATCHES_ALL).find(settings)?.groupValues?.get(1)
            ?: return emptyList()
        return Regex("""["'](:[^"']+)["']""").findAll(block).map { it.groupValues[1] }.toList()
    }

    /** `[plugins]` and `[libraries]` of a version catalog, as `id:version` / `group:name:version`. */
    private class Catalog(file: File) {
        private val toml = file.takeIf { it.isFile }?.let { Toml.parse(it.toPath()) }?.takeIf { !it.hasErrors() }
        private val versions = toml?.getTable("versions")

        private fun version(value: Any?): String? = when (value) {
            is String -> value
            is TomlTable -> value.getString("ref")?.let { versions?.get(listOf(it)) as? String }
                ?: listOf("strictly", "require", "prefer").firstNotNullOfOrNull { value.getString(it) }
            else -> null
        }

        /** `libs.plugins.android.library` accessor path → the alias declared as `android-library`/`android.library`. */
        private fun entry(table: String, accessor: String): Any? {
            val section = toml?.getTable(table) ?: return null
            val key = accessor.lowercase().filter { it.isLetterOrDigit() }
            return section.keySet().firstOrNull { it.lowercase().filter { c -> c.isLetterOrDigit() } == key }?.let { section.get(listOf(it)) }
        }

        fun plugin(accessor: String): String? = when (val p = entry("plugins", accessor)) {
            is String -> p
            is TomlTable -> p.getString("id")?.let { id -> "$id:${version(p.get(listOf("version"))) ?: "?"}" }
            else -> null
        }

        fun library(accessor: String): String? = when (val l = entry("libraries", accessor)) {
            is String -> l
            is TomlTable -> (l.getString("module") ?: l.getString("group")?.let { g -> l.getString("name")?.let { "$g:$it" } })
                ?.let { "$it:${version(l.get(listOf("version"))) ?: "?"}" }
            else -> null
        }
    }
}
