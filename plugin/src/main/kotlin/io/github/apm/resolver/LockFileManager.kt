package dev.klone.resolver

import dev.klone.model.GitRef
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
        val resolvedAt: String
    )

    fun write(entries: List<LockEntry>) {
        val locked = LockFile(
            version = 1,
            dependencies = entries.map {
                LockedDependency(
                    url = it.url,
                    refType = it.refType,
                    refValue = it.refValue,
                    resolvedCommit = it.resolvedCommit,
                    resolvedModuleCoordinates = it.resolvedModuleCoordinates,
                    resolvedAt = Instant.now().toString()
                )
            }
        )
        lockFile.writeText(json.encodeToString(LockFile.serializer(), locked))
    }

    fun read(): LockFile? {
        if (!lockFile.exists()) return null
        return runCatching { json.decodeFromString(LockFile.serializer(), lockFile.readText()) }.getOrNull()
    }

    data class LockEntry(
        val url: String,
        val refType: String,
        val refValue: String,
        val resolvedCommit: String,
        val resolvedModuleCoordinates: String?
    )
}

fun GitRef.toLockEntry(url: String, commit: String, module: String?): LockFileManager.LockEntry {
    return when (this) {
        is GitRef.Tag -> LockFileManager.LockEntry(url, "tag", name, commit, module)
        is GitRef.Branch -> LockFileManager.LockEntry(url, "branch", name, commit, module)
        is GitRef.Commit -> LockFileManager.LockEntry(url, "commit", hash, commit, module)
    }
}
