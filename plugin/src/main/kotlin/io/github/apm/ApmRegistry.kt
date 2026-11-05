package dev.klone

import dev.klone.model.gitUrlKey
import java.io.File

/**
 * In-process registry mapping (git URL, moduleName) -> resolved module information.
 * Populated by KloneSettingsPlugin during settings phase,
 * read by gitImplementation() during project evaluation phase.
 *
 * Key is composed of normalized URL + module name (null = root module).
 */
object KloneRegistry {

    data class Entry(
        val url: String,
        val moduleCoordinates: String?,
        val localDir: File
    )

    private val entries = mutableMapOf<String, Entry>()
    private val urlToModules = mutableMapOf<String, LinkedHashSet<String?>>()

    /** Host build root — relative localImplementation() paths resolve against it. */
    @Volatile
    var rootDir: File? = null

    /** True when the build only runs kloneClean: nothing was resolved and declarations are no-ops. */
    @Volatile
    var resolutionSkipped: Boolean = false

    fun register(url: String, moduleName: String?, entry: Entry) {
        entries[key(url, moduleName)] = entry
        urlToModules.getOrPut(url.normalizeUrl()) { linkedSetOf() }.add(moduleName)
    }

    fun find(url: String, moduleName: String? = null): Entry? = entries[key(url, moduleName)]

    fun allModulesFor(url: String): List<String?> =
        urlToModules[url.normalizeUrl()]?.toList() ?: emptyList()

    fun all(): Map<String, Entry> = entries.toMap()

    fun clear() {
        entries.clear()
        urlToModules.clear()
        resolutionSkipped = false
    }

    fun localKey(dir: File): String = "local:${dir.canonicalPath}"

    /** Same rule in settings and project phase so keys always match. */
    fun resolveLocalDir(path: String, rootDir: File): File {
        val file = File(path)
        return (if (file.isAbsolute) file else File(rootDir, path)).canonicalFile
    }

    private fun key(url: String, moduleName: String?): String =
        "${url.normalizeUrl()}#${moduleName ?: ""}"

    private fun String.normalizeUrl(): String =
        if (startsWith("local:")) this else gitUrlKey(this).lowercase()
}
