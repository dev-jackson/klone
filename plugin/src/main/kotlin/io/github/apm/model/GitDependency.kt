package dev.klone.model

/** Shared by git and local dependencies: which modules of the source build to substitute. */
interface ModuleSelection {
    // Explicit Maven coordinates — overrides auto-detection, maps to the root project
    val moduleCoordinates: String?
    // null = auto-detect all submodules from repo settings
    // emptyList = root module only
    // listOf("x","y") = specific named submodules
    val submoduleNames: List<String>?
}

data class GitDependency(
    val url: String,
    val ref: GitRef = GitRef.Default,
    override val moduleCoordinates: String? = null,
    override val submoduleNames: List<String>? = null,
    // Tag naming, e.g. "sdk-{version}" when the repo tags releases as "sdk-1.4.0"
    val tagPattern: String? = null,
    // Gradle build inside the repo, e.g. "android/sdk" in a monorepo
    val dir: String? = null
) : ModuleSelection {
    val repoName: String
        get() = url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")

    /** Stable `<host>/<path>` used as cache directory. Same repo spelled differently → same key. */
    val cacheKey: String
        get() = gitUrlKey(url)
}

sealed class GitRef {
    data class Tag(val name: String) : GitRef()
    data class Branch(val name: String) : GitRef()
    data class Commit(val hash: String) : GitRef()
    /** Highest tag matching a version range: `^1.4`, `~1.4.2`, `1.+`, `[1.0,2.0)`, `>=1.2 <2`. */
    data class Range(val spec: String) : GitRef()
    /** No ref declared — follow the remote's default branch (origin/HEAD). */
    object Default : GitRef() {
        override fun toString() = "Default"
    }

    val type: String
        get() = when (this) {
            is Tag -> "tag"
            is Branch -> "branch"
            is Commit -> "commit"
            is Range -> "range"
            Default -> "default"
        }

    val value: String
        get() = when (this) {
            is Tag -> name
            is Branch -> name
            is Commit -> hash
            is Range -> spec
            Default -> "HEAD"
        }

    companion object {
        /** A version string becomes a [Range] when it is one (`^1.4`, `1.+`, ...), otherwise an exact [Tag]. */
        fun version(value: String): GitRef = if (SemVer.isRange(value)) Range(value) else Tag(value)

        /** SPM `from:` — this version up to (not including) the next major. */
        fun upToNextMajor(from: String): GitRef = Range(SemVer.upToNextMajor(from))

        /** SPM `.upToNextMinor(from:)`. */
        fun upToNextMinor(from: String): GitRef = Range(SemVer.upToNextMinor(from))

        /**
         * One ref from the SPM-style parameters; null when none given.
         * Fails when several are combined, so mistakes surface in the settings phase.
         */
        fun fromParams(
            from: String? = null, exact: String? = null, upToNextMinor: String? = null, version: String? = null,
            tag: String? = null, branch: String? = null, commit: String? = null
        ): GitRef? {
            val refs = listOfNotNull(
                from?.let { upToNextMajor(it) }, exact?.let { Tag(it) }, upToNextMinor?.let { upToNextMinor(it) },
                version?.let { version(it) }, tag?.let { Tag(it) }, branch?.let { Branch(it) }, commit?.let { Commit(it) }
            )
            require(refs.size <= 1) { "Use only one of from, exact, upToNextMinor, version, tag, branch, commit" }
            return refs.firstOrNull()
        }

        fun of(type: String, value: String): GitRef = when (type) {
            "tag" -> Tag(value)
            "branch" -> Branch(value)
            "commit" -> Commit(value)
            "range" -> Range(value)
            else -> Default
        }
    }

    fun describe(): String = when (this) {
        is Commit -> "commit: ${hash.take(8)}"
        is Range -> "version: $spec"
        Default -> "default branch"
        else -> "$type: $value"
    }
}

/**
 * Normalizes any git URL form to `<host>/<path>`:
 *   https://user:token@github.com/a/b.git → github.com/a/b
 *   git@github.com:a/b.git               → github.com/a/b
 *   ssh://git@host:22/a/b                → host/a/b
 *   file:///tmp/repos/b  or  /tmp/repos/b → file/tmp/repos/b
 */
fun gitUrlKey(url: String): String {
    val trimmed = url.trim().trimEnd('/').removeSuffix(".git")
    val scp = Regex("""^[\w.-]+@([^:/]+):(.+)$""").find(trimmed)
    val (host, path) = when {
        scp != null -> scp.groupValues[1] to scp.groupValues[2]
        trimmed.startsWith("file://") -> "file" to trimmed.removePrefix("file://")
        trimmed.contains("://") -> {
            val rest = trimmed.substringAfter("://")
            val authority = rest.substringBefore('/')
            authority.substringAfterLast('@').substringBefore(':') to rest.substringAfter('/', "")
        }
        else -> "file" to trimmed
    }
    if (host == "file") {
        // Local remotes: the full absolute path would blow Windows' 260-char path limit inside the cache
        val name = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
        return "file/$name-$hash"
    }
    val safePath = path.replace('\\', '/').trim('/').split('/')
        .joinToString("/") { segment -> segment.replace(Regex("""[<>:"|?*]"""), "_") }
    return "$host/$safePath"
}

/** `file:///` URL for a local directory on any OS (`file:///tmp/x`, `file:///C:/x`). */
fun fileUrl(dir: java.io.File): String = "file:///" + dir.absoluteFile.invariantSeparatorsPath.trimStart('/')

/** Deletes a tree even when git made pack files read-only (Windows refuses to delete those). */
fun java.io.File.deleteTree(): Boolean {
    if (!exists()) return true
    walkBottomUp().forEach { it.setWritable(true); it.delete() }
    return !exists()
}
