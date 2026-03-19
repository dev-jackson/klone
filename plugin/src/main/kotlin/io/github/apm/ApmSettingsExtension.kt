package dev.klone

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.LocalDependency

open class KloneSettingsExtension {
    internal val dependencies = mutableListOf<GitDependency>()
    internal val localDependencies = mutableListOf<LocalDependency>()

    fun git(url: String, configure: GitDependencyBuilder.() -> Unit) {
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
    private var ref: GitRef? = null
    private var moduleCoordinates: String? = null

    fun from(version: String) { ref = GitRef.Tag(version) }
    fun tag(name: String) { ref = GitRef.Tag(name) }
    fun branch(name: String) { ref = GitRef.Branch(name) }
    fun commit(hash: String) { ref = GitRef.Commit(hash) }
    fun module(coordinates: String) { moduleCoordinates = coordinates }

    fun build(): GitDependency {
        val resolvedRef = ref ?: GitRef.Branch("main")
        return GitDependency(url = url, ref = resolvedRef, moduleCoordinates = moduleCoordinates)
    }
}
