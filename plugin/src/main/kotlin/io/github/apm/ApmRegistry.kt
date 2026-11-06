package dev.klone

import dev.klone.model.gitUrlKey
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.invocation.Gradle
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry mapping (git URL, moduleName) -> resolved module information.
 * Populated by KloneSettingsPlugin during settings phase,
 * read by gitImplementation() during project evaluation phase.
 *
 * Each build has its own scope (keyed by its root dir): a cloned repo that applies Klone
 * itself is an included build whose settings run after the host's and must not touch the
 * host's entries.
 */
object KloneRegistry {

    data class Entry(
        val url: String,
        val moduleCoordinates: String?,
        val localDir: File
    )

    /** Commit (and tag) the root build resolved a repo to — nested Klone builds reuse it. */
    data class Resolution(val commit: String, val tag: String?)

    class Scope(val rootDir: File) {
        private val entries = ConcurrentHashMap<String, Entry>()
        private val resolutions = ConcurrentHashMap<String, Resolution>()
        private val urlToModules = ConcurrentHashMap<String, LinkedHashSet<String?>>()

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

        fun resolved(url: String, resolution: Resolution) {
            resolutions[url.normalizeUrl()] = resolution
        }

        fun resolution(url: String): Resolution? = resolutions[url.normalizeUrl()]

        /** Module name and entry of every module registered for [url]. */
        fun entriesFor(url: String): List<Pair<String?, Entry>> =
            allModulesFor(url).mapNotNull { name -> find(url, name)?.let { name to it } }
    }

    // Root build of each running build, by its Gradle instance (one per build invocation)
    private val roots = Collections.synchronizedMap(WeakHashMap<Gradle, Scope>())

    /** The root (host) build's scope: nested Klone builds look up the graph it resolved. */
    fun registerRoot(gradle: Gradle, scope: Scope) {
        roots[gradle] = scope
    }

    fun rootScope(gradle: Gradle): Scope? = roots[generateSequence(gradle) { it.parent }.last()]

    private val scopes = ConcurrentHashMap<String, Scope>()
    private val handlers = Collections.synchronizedMap(WeakHashMap<DependencyHandler, Scope>())

    /** Fresh scope for the build at [rootDir] — the daemon outlives builds, old entries must go. */
    fun reset(rootDir: File): Scope = Scope(rootDir).also { scopes[rootDir.canonicalPath] = it }

    fun scope(rootDir: File): Scope? = scopes[rootDir.canonicalPath]

    /** Called for every project of a build so its `dependencies {}` reads that build's scope. */
    fun bind(dependencies: DependencyHandler, scope: Scope) {
        handlers[dependencies] = scope
    }

    /** Kotlin DSL passes a `DependencyHandlerScope` wrapping the project's handler: unwrap it. */
    fun scopeOf(dependencies: DependencyHandler): Scope? = handlers[dependencies] ?: runCatching {
        dependencies.javaClass.getMethod("getDependencies").invoke(dependencies) as? DependencyHandler
    }.getOrNull()?.let { handlers[it] }

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
