package dev.klone.toml

import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File

/**
 * Reads `[versions]` from the host's Gradle version catalogs (`gradle/<name>.versions.toml`)
 * so the scanner can resolve `libs.versions.retrofit.get()` before projects are configured.
 */
object VersionCatalogReader {

    /** Accessor path → version, e.g. `"libs.versions.kotlin.coroutines"` → `"1.8.0"`. */
    fun read(rootDir: File): Map<String, String> {
        val files = File(rootDir, "gradle").listFiles { f -> f.isFile && f.name.endsWith(".versions.toml") }
            ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (file in files.sortedBy { it.name }) {
            val catalogName = file.name.removeSuffix(".versions.toml")
            val toml = Toml.parse(file.toPath())
            if (toml.hasErrors()) continue // Gradle itself reports catalog syntax errors
            val versions = toml.getTable("versions") ?: continue
            for (alias in versions.keySet()) {
                val version = versionOf(versions, alias) ?: continue
                result["$catalogName.versions.${accessorPath(alias)}"] = version
            }
        }
        return result
    }

    /** Resolves `libs.versions.x.y`, `libs.versions.x.y.get()` or a plain string literal. */
    fun resolve(expr: String, versions: Map<String, String>): String? {
        val path = expr.trim().removeSuffix(".get()").removeSuffix(".orNull").replace(Regex("""\s+"""), "")
        return versions[path]
    }

    /** Same mapping Gradle uses for accessors: `-`, `_` and `.` all become `.`. */
    internal fun accessorPath(alias: String): String = alias.replace('-', '.').replace('_', '.')

    private fun versionOf(table: TomlTable, alias: String): String? =
        when (val value = table.get(listOf(alias))) {
            is String -> value
            is TomlTable -> listOf("strictly", "require", "prefer").firstNotNullOfOrNull { value.getString(it) }
            else -> null
        }
}
