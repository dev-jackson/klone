package dev.klone.resolver

import org.gradle.api.logging.Logger
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File

/**
 * Android Gradle Plugin and Kotlin versions a build declares. A composite build loads build
 * plugins once, so a cloned library on another AGP/Kotlin line typically fails with
 * "plugin is already on the classpath with an unknown version" — warn before that happens.
 */
object PluginVersions {

    const val AGP = "Android Gradle Plugin"
    const val KOTLIN = "Kotlin"

    fun detect(dir: File): Map<String, String> {
        val found = mutableMapOf<String, String>()
        fun put(key: String, version: String?) { if (version != null && key !in found) found[key] = version }

        File(dir, "gradle/libs.versions.toml").takeIf { it.isFile }?.let { file ->
            val toml = Toml.parse(file.toPath())
            if (!toml.hasErrors()) {
                val versions = toml.getTable("versions")
                fun versionOf(v: Any?): String? = when (v) {
                    is String -> v
                    is TomlTable -> v.getString("ref")?.let { versions?.get(listOf(it)) as? String }
                        ?: listOf("strictly", "require", "prefer").firstNotNullOfOrNull { v.getString(it) }
                    else -> null
                }
                toml.getTable("plugins")?.let { plugins ->
                    for (alias in plugins.keySet()) {
                        val p = plugins.get(listOf(alias)) as? TomlTable ?: continue
                        val id = p.getString("id") ?: continue
                        val version = versionOf(p.get(listOf("version")))
                        if (id.startsWith("com.android.")) put(AGP, version)
                        if (id.startsWith("org.jetbrains.kotlin.")) put(KOTLIN, version)
                    }
                }
                put(AGP, listOf("agp", "android-gradle-plugin", "androidGradlePlugin").firstNotNullOfOrNull { versions?.get(listOf(it)) as? String })
                put(KOTLIN, versions?.get(listOf("kotlin")) as? String)
            }
        }

        val scripts = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
            .map { File(dir, it) }.filter { it.isFile }.joinToString("\n") { ScriptParser.stripComments(it.readText()) }
        put(AGP, Regex("""com\.android\.tools\.build:gradle:([\w.\-]+)""").find(scripts)?.groupValues?.get(1))
        put(AGP, Regex("""id\s*\(?\s*["']com\.android\.[\w.]+["']\s*\)?\s*version\s*\(?\s*["']([^"']+)["']""").find(scripts)?.groupValues?.get(1))
        put(KOTLIN, Regex("""kotlin-gradle-plugin:([\w.\-]+)""").find(scripts)?.groupValues?.get(1))
        put(KOTLIN, Regex("""(?:kotlin\s*\(\s*["'][\w.]+["']\s*\)|id\s*\(?\s*["']org\.jetbrains\.kotlin\.[\w.]+["']\s*\)?)\s*version\s*\(?\s*["']([^"']+)["']""").find(scripts)?.groupValues?.get(1))
        return found
    }

    /** Messages for plugins whose major.minor differ between host and library. */
    fun incompatibilities(host: Map<String, String>, library: Map<String, String>): List<String> =
        listOf(AGP, KOTLIN).mapNotNull { key ->
            val h = host[key] ?: return@mapNotNull null
            val l = library[key] ?: return@mapNotNull null
            if (line(h) == line(l)) null else "$key $l (host uses $h)"
        }

    fun warnIfIncompatible(host: Map<String, String>, libraryDir: File, url: String, logger: Logger) {
        val problems = incompatibilities(host, detect(libraryDir))
        if (problems.isEmpty()) return
        logger.warn(
            "[Klone] $url declares ${problems.joinToString(" and ")}.\n" +
                "A composite build loads build plugins once: align the versions (e.g. a branch/tag of the library " +
                "on your versions) or expect errors like 'plugin is already on the classpath with an unknown version'."
        )
    }

    private fun line(version: String) = version.split('.', '-').take(2).joinToString(".")
}
