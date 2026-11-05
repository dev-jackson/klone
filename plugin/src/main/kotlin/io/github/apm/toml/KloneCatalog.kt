package dev.klone.toml

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.LocalDependency
import org.gradle.api.GradleException
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.File

/**
 * Git / local sources for libraries of the host's own `gradle/libs.versions.toml`.
 *
 * Gradle rejects unknown sections and keys in a catalog, but ignores extra keys in `[metadata]`,
 * so sources live there and the libraries stay standard (`implementation(libs.retrofit)`):
 *
 * ```toml
 * [versions]
 * retrofit = "2.9.0"
 *
 * [libraries]
 * retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
 * gson     = { module = "com.squareup.retrofit2:converter-gson", version.ref = "retrofit" }
 * design   = { module = "com.org:design" }
 * utils    = { module = "com.org:utils" }
 *
 * [metadata]
 * klone.retrofit = "https://github.com/square/retrofit"        # exact library version as tag
 * klone.okhttp   = { git = "https://github.com/square/okhttp", from = "4.12.0" }   # SPM: up to next major
 * klone.gson     = { git = "https://github.com/square/retrofit", module = "converter-gson" }
 * klone.design   = { git = "git@github.com:org/ds.git", branch = "main" }
 * klone.utils    = { path = "../utils" }
 * ```
 */
class KloneCatalog(val gitDependencies: List<GitDependency>, val localDependencies: List<LocalDependency>) {

    companion object {
        const val DEFAULT_PATH = "gradle/libs.versions.toml"

        private val REF_KEYS = listOf("from", "exact", "upToNextMinor", "version", "tag", "branch", "commit")
        private val SOURCE_KEYS = setOf("git", "path", "module", "tagPattern", "dir") + REF_KEYS

        fun parse(file: File): KloneCatalog {
            val empty = KloneCatalog(emptyList(), emptyList())
            if (!file.exists()) return empty
            val toml = Toml.parse(file.toPath())
            if (toml.hasErrors()) return empty // Gradle reports catalog syntax errors itself
            val sources = toml.getTable("metadata")?.get(listOf("klone")) as? TomlTable ?: return empty

            val git = mutableListOf<GitDependency>()
            val local = mutableListOf<LocalDependency>()
            for (alias in sources.keySet().sorted()) {
                when (val dep = parseSource(file, toml, alias, sources.get(listOf(alias)))) {
                    is GitDependency -> git += dep
                    is LocalDependency -> local += dep
                }
            }
            return KloneCatalog(git, local)
        }

        private fun parseSource(file: File, toml: TomlParseResult, alias: String, raw: Any?): Any {
            fun fail(msg: String): Nothing =
                throw GradleException("[Klone] Invalid ${file.name}: metadata.klone.$alias: $msg")

            val library = libraryOf(toml, alias)
                ?: fail("no library '$alias' in [libraries] — declare it there, Klone only adds its source")
            val coordinates = library.first

            val source: TomlTable? = raw as? TomlTable
            val url = when (raw) {
                is String -> raw
                is TomlTable -> raw.get(listOf("git"))?.let { it as? String ?: fail("git must be a string") }
                else -> fail("must be a git URL string or a table { git = \"...\" }")
            }
            if (source != null) {
                val unknown = source.keySet() - SOURCE_KEYS
                if (unknown.isNotEmpty()) fail("unknown keys $unknown (allowed: $SOURCE_KEYS)")
            }
            fun str(key: String): String? = source?.get(listOf(key))?.let { it as? String ?: fail("$key must be a string") }

            val module = str("module")?.let { listOf(it) }
            val path = str("path")
            if ((url == null) == (path == null)) fail("declare exactly one of `git` or `path`")
            if (path != null) {
                if (REF_KEYS.any { source?.contains(listOf(it)) == true }) fail("local `path` sources cannot declare a git ref")
                return LocalDependency(path, coordinates, module)
            }

            val refs = REF_KEYS.associateWith { str(it) }
            val declared = try {
                GitRef.fromParams(refs["from"], refs["exact"], refs["upToNextMinor"], refs["version"], refs["tag"], refs["branch"], refs["commit"])
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "invalid ref")
            }
            // No ref: the library's own catalog version (exact, or a range like "1.+" / "[1.0,2.0)")
            val ref = declared ?: library.second?.let { GitRef.version(it) } ?: GitRef.Default
            return GitDependency(url!!, ref, coordinates, module, tagPattern = str("tagPattern"), dir = str("dir"))
        }

        /** `group:name` and version of a `[libraries]` entry in any of Gradle's notations. */
        private fun libraryOf(toml: TomlParseResult, alias: String): Pair<String, String?>? {
            val versions = toml.getTable("versions")
            fun versionOf(v: Any?): String? = when (v) {
                is String -> v
                is TomlTable -> v.getString("ref")?.let { ref -> versionOf(versions?.get(listOf(ref))) }
                    ?: listOf("strictly", "require", "prefer").firstNotNullOfOrNull { v.getString(it) }
                else -> null
            }
            return when (val lib = toml.getTable("libraries")?.get(listOf(alias))) {
                is String -> lib.split(':').let { p -> if (p.size < 2) null else "${p[0]}:${p[1]}" to p.getOrNull(2) }
                is TomlTable -> {
                    val coords = lib.getString("module")
                        ?: lib.getString("group")?.let { g -> lib.getString("name")?.let { "$g:$it" } }
                        ?: return null
                    coords to versionOf(lib.get(listOf("version")))
                }
                else -> null
            }
        }
    }
}
