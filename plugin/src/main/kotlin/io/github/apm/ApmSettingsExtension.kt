package dev.klone

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.LocalDependency
import java.io.File

open class KloneSettingsExtension {
    internal val dependencies = mutableListOf<GitDependency>()
    internal val localDependencies = mutableListOf<LocalDependency>()

    /** Version catalog whose `[metadata] klone.*` entries declare git/local sources. Default: gradle/libs.versions.toml */
    var catalogFile: File? = null

    internal val propagatedProperties = mutableListOf<String>()

    /**
     * Host properties to copy into cloned builds even if they look like secrets (`*password*`, `*token*`...),
     * also read from other `*.properties` files in the root. Glob patterns: `propagateProperties("artifactory*")`.
     * They end up in the cached clone's gradle.properties in plain text.
     */
    fun propagateProperties(vararg patterns: String) { propagatedProperties += patterns }

    /** Where repos are cached. Default: `$GRADLE_USER_HOME/caches/klone` (env `KLONE_CACHE_DIR` wins). */
    var cacheDir: File? = null

    fun git(url: String, configure: GitDependencyBuilder.() -> Unit = {}) {
        val builder = GitDependencyBuilder(url)
        builder.configure()
        dependencies.add(builder.build())
    }

    fun local(path: String, configure: LocalDependencyBuilder.() -> Unit = {}) {
        val builder = LocalDependencyBuilder(path)
        builder.configure()
        localDependencies.add(builder.build())
    }
}

class LocalDependencyBuilder(private val path: String) {
    private var moduleCoordinates: String? = null
    private var submoduleNames: List<String>? = null

    fun module(coordinates: String) { moduleCoordinates = coordinates }
    fun modules(names: List<String>) { submoduleNames = names }

    fun build(): LocalDependency = LocalDependency(
        path = path,
        moduleCoordinates = moduleCoordinates,
        submoduleNames = submoduleNames
    )
}

class GitDependencyBuilder(private val url: String) {
    private var ref: GitRef = GitRef.Default
    private var moduleCoordinates: String? = null
    private var submoduleNames: List<String>? = null
    private var tagPattern: String? = null
    private var dir: String? = null

    /** SPM `from:` — this version up to the next major. */
    fun from(version: String) { ref = GitRef.upToNextMajor(version) }
    fun exact(version: String) { ref = GitRef.Tag(version) }
    fun upToNextMinor(version: String) { ref = GitRef.upToNextMinor(version) }
    /** Any range: `^1.4`, `~1.4.2`, `>=1.0.0 <2.0.0`, `1.+`, `[1.0,2.0)`. */
    fun version(spec: String) { ref = GitRef.version(spec) }
    fun tag(name: String) { ref = GitRef.Tag(name) }
    fun branch(name: String) { ref = GitRef.Branch(name) }
    fun commit(hash: String) { ref = GitRef.Commit(hash) }
    fun module(coordinates: String) { moduleCoordinates = coordinates }
    fun modules(names: List<String>) { submoduleNames = names }
    fun tagPattern(pattern: String) { tagPattern = pattern }
    fun dir(path: String) { dir = path }

    fun build(): GitDependency = GitDependency(
        url = url, ref = ref, moduleCoordinates = moduleCoordinates, submoduleNames = submoduleNames,
        tagPattern = tagPattern, dir = dir
    )
}
