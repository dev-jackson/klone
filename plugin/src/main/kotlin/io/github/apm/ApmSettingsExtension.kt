package dev.klone

import dev.klone.model.GitDependency
import dev.klone.model.GitRef

open class KloneSettingsExtension {
    internal val dependencies = mutableListOf<GitDependency>()

    fun git(url: String, configure: GitDependencyBuilder.() -> Unit) {
        val builder = GitDependencyBuilder(url)
        builder.configure()
        dependencies.add(builder.build())
    }
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
