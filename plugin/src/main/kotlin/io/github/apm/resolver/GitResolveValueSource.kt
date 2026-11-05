package dev.klone.resolver

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * Runs git resolution as a Gradle [ValueSource]: the only sanctioned way to start processes at
 * configuration time with the configuration cache. On a cache hit Gradle re-runs it (local
 * rev-parse for tags/locked commits) and reuses the cache when the commit is unchanged.
 */
abstract class GitResolveValueSource : ValueSource<String, GitResolveValueSource.Params> {

    interface Params : ValueSourceParameters {
        val url: Property<String>
        val refType: Property<String>
        val refValue: Property<String>
        val lockedCommit: Property<String>
        val lockedTag: Property<String>
        val tagPattern: Property<String>
        val cacheDir: Property<String>
        val offline: Property<Boolean>
        val refresh: Property<Boolean>
    }

    /** "<commit> <tag>" (tag may be empty). */
    override fun obtain(): String {
        val p = parameters
        val dep = GitDependency(p.url.get(), GitRef.of(p.refType.get(), p.refValue.get()), tagPattern = p.tagPattern.orNull)
        val resolved = GitResolver(File(p.cacheDir.get()), p.offline.get(), p.refresh.get())
            .resolve(dep, p.lockedCommit.orNull, p.lockedTag.orNull)
        return "${resolved.commitHash} ${resolved.tag.orEmpty()}"
    }

    companion object {
        fun resolve(
            providers: ProviderFactory,
            dep: GitDependency,
            locked: LockFileManager.LockedDependency?,
            cacheDir: File,
            offline: Boolean,
            refresh: Boolean
        ): GitResolver.ResolvedDependency {
            val result = providers.of(GitResolveValueSource::class.java) { spec ->
                spec.parameters.url.set(dep.url)
                spec.parameters.refType.set(dep.ref.type)
                spec.parameters.refValue.set(dep.ref.value)
                spec.parameters.lockedCommit.set(locked?.resolvedCommit)
                spec.parameters.lockedTag.set(locked?.resolvedTag)
                spec.parameters.tagPattern.set(dep.tagPattern)
                spec.parameters.cacheDir.set(cacheDir.absolutePath)
                spec.parameters.offline.set(offline)
                spec.parameters.refresh.set(refresh)
            }.get()
            val commit = result.substringBefore(' ')
            val tag = result.substringAfter(' ', "").ifEmpty { null }
            return GitResolver.ResolvedDependency(File(cacheDir, dep.cacheKey), commit, tag)
        }
    }
}
