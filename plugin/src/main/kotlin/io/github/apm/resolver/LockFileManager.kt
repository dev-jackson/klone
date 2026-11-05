package dev.klone.resolver

import dev.klone.model.GitRef
import dev.klone.model.gitUrlKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

class LockFileManager(private val projectDir: File) {

    private val lockFile = File(projectDir, "klone.resolved")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class LockFile(
        val version: Int = 1,
        val dependencies: List<LockedDependency> = emptyList()
    )

    @Serializable
    data class LockedDependency(
        val url: String,
        val refType: String,
        val refValue: String,
        val resolvedCommit: String,
        val resolvedModuleCoordinates: String?,
        val resolvedAt: String,
        // Tag the commit came from (tags and version ranges)
        val resolvedTag: String? = null
    )

    private val existing: LockFile? by lazy { read() }

    /** Entry pinned for this url + ref, or null when the ref changed or was never locked. */
    fun locked(url: String, ref: GitRef): LockedDependency? {
        val key = gitUrlKey(url).lowercase()
        val forUrl = existing?.dependencies?.filter { gitUrlKey(it.url).lowercase() == key }.orEmpty()
        forUrl.firstOrNull { it.refType == ref.type && it.refValue == ref.value }?.let { return it }

        // Klone 1.x locked `from = "2.9.0"` as tag 2.9.0. In 2.x the same declaration is the range
        // ">=2.9.0 <3.0.0": keep the old commit instead of silently upgrading on the first build.
        val lowerBound = (ref as? GitRef.Range)?.let { LEGACY_FROM.find(it.spec)?.groupValues?.get(1) } ?: return null
        return forUrl.firstOrNull { it.refType == "tag" && it.refValue == lowerBound }
            ?.let { it.copy(resolvedTag = it.resolvedTag ?: it.refValue) }
    }

    private companion object {
        val LEGACY_FROM = Regex("""^>=(\S+) <\S+$""")
    }

    fun lockedCommit(url: String, ref: GitRef): String? = locked(url, ref)?.resolvedCommit

    val exists: Boolean get() = lockFile.exists()

    /**
     * Writes entries sorted by url. `resolvedAt` is kept for unchanged entries and the file is
     * only rewritten when content changes — a no-op build never dirties the lock file.
     */
    fun write(entries: List<LockEntry>) {
        val previous = existing?.dependencies.orEmpty()
        val now = Instant.now().toString()
        val locked = LockFile(
            version = 1,
            dependencies = entries.sortedBy { it.url }.map { entry ->
                val same = previous.firstOrNull {
                    it.url == entry.url && it.refType == entry.refType && it.refValue == entry.refValue &&
                        it.resolvedCommit == entry.resolvedCommit &&
                        it.resolvedModuleCoordinates == entry.resolvedModuleCoordinates && it.resolvedTag == entry.resolvedTag
                }
                LockedDependency(
                    url = entry.url,
                    refType = entry.refType,
                    refValue = entry.refValue,
                    resolvedCommit = entry.resolvedCommit,
                    resolvedModuleCoordinates = entry.resolvedModuleCoordinates,
                    resolvedAt = same?.resolvedAt ?: now,
                    resolvedTag = entry.resolvedTag
                )
            }
        )
        val text = json.encodeToString(LockFile.serializer(), locked) + "\n"
        if (!lockFile.exists() || lockFile.readText() != text) lockFile.writeText(text)
    }

    fun read(): LockFile? {
        if (!lockFile.exists()) return null
        return runCatching { json.decodeFromString(LockFile.serializer(), lockFile.readText()) }.getOrNull()
    }

    /** Entries that would change: what a frozen (CI) build refuses. */
    fun diff(entries: List<LockEntry>): List<String> {
        val previous = existing?.dependencies.orEmpty()
        return entries.mapNotNull { e ->
            val old = previous.firstOrNull { it.url == e.url }
            when {
                old == null -> "${e.url}: not in klone.resolved"
                old.refType != e.refType || old.refValue != e.refValue -> "${e.url}: ref changed (${old.refType} ${old.refValue} → ${e.refType} ${e.refValue})"
                old.resolvedCommit != e.resolvedCommit -> "${e.url}: commit ${old.resolvedCommit.take(8)} → ${e.resolvedCommit.take(8)}"
                else -> null
            }
        } + previous.filter { p -> entries.none { it.url == p.url } }.map { "${it.url}: no longer declared" }
    }

    data class LockEntry(
        val url: String,
        val refType: String,
        val refValue: String,
        val resolvedCommit: String,
        val resolvedModuleCoordinates: String?,
        val resolvedTag: String? = null
    )
}

fun GitRef.toLockEntry(url: String, commit: String, module: String?, tag: String? = null): LockFileManager.LockEntry =
    LockFileManager.LockEntry(url, type, value, commit, module, tag)
