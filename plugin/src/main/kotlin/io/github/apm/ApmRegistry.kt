package dev.klone

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
    private val urlToModules = mutableMapOf<String, MutableList<String?>>()

    fun register(url: String, moduleName: String?, entry: Entry) {
        entries[key(url, moduleName)] = entry
        urlToModules.getOrPut(url.normalizeUrl()) { mutableListOf() }.add(moduleName)
    }

    fun find(url: String, moduleName: String? = null): Entry? = entries[key(url, moduleName)]

    fun allModulesFor(url: String): List<String?> =
        urlToModules[url.normalizeUrl()] ?: emptyList()

    fun all(): Map<String, Entry> = entries.toMap()

    fun clear() {
        entries.clear()
        urlToModules.clear()
    }

    private fun key(url: String, moduleName: String?): String =
        "${url.normalizeUrl()}#${moduleName ?: ""}"

    private fun String.normalizeUrl(): String {
        if (startsWith("local:")) return this
        val normalized = when {
            startsWith("git@") -> {
                // git@github.com:user/repo.git → github.com/user/repo
                val host = removePrefix("git@").substringBefore(':')
                val path = substringAfter(':').removeSuffix(".git")
                "$host/$path"
            }
            else -> {
                val noScheme = removePrefix("https://").removePrefix("http://")
                // Strip embedded auth: user:token@host/path → host/path
                if (noScheme.contains('@')) noScheme.substringAfter('@') else noScheme
            }
        }
        return normalized.trimEnd('/').removeSuffix(".git").lowercase()
    }
}
