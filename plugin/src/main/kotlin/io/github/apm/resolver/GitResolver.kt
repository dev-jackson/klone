package dev.klone.resolver

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.SemVer
import dev.klone.model.deleteTree
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Keeps one shallow checkout per repo under [cacheDir] and moves it to the requested ref.
 *
 * Every ref is fetched explicitly (never relying on clone's single-branch refspec), tags and
 * commits already present skip the network, and failures leave the cache in a usable state.
 *
 * @param refresh `--refresh-dependencies`: re-fetch even refs that are already cached.
 */
class GitResolver(
    private val cacheDir: File,
    private val offline: Boolean = false,
    private val refresh: Boolean = false,
    private val timeoutMinutes: Long = 10,
    private val logger: Logger = Logging.getLogger(GitResolver::class.java)
) {

    data class ResolvedDependency(
        val localDir: File,
        val commitHash: String,
        // Tag the commit came from (tags and ranges) — recorded in klone.resolved
        val tag: String? = null
    )

    /**
     * @param lockedCommit commit from klone.resolved; when set it wins over the declared ref
     *                     so branch and range dependencies stay reproducible.
     */
    fun resolve(dep: GitDependency, lockedCommit: String? = null, lockedTag: String? = null): ResolvedDependency {
        val localDir = File(cacheDir, dep.cacheKey)
        return withCacheLock(localDir) {
            // Fast path, every build after the first: the checkout already is the locked commit.
            // Reading .git/HEAD avoids spawning git (~20 ms per process, several per repo).
            if (lockedCommit != null && !refresh && checkedOutCommit(localDir) == lockedCommit && submodulesReady(localDir)) {
                markUsed(localDir)
                return@withCacheLock ResolvedDependency(localDir, lockedCommit, lockedTag)
            }
            if (!isValidRepo(localDir)) {
                if (localDir.exists()) {
                    logger.warn("[Klone] Cache at ${localDir.absolutePath} is corrupt — re-cloning")
                    if (!localDir.deleteTree()) throw KloneGitException("Could not delete corrupt cache ${localDir.absolutePath}")
                }
                if (offline) throw KloneGitException(
                    "${dep.url} is not cached and Gradle runs with --offline. Run once online first."
                )
                init(dep.url, localDir)
            }

            val (commit, tag) = if (lockedCommit != null) {
                ensureCommit(localDir, dep.url, GitRef.Commit(lockedCommit)) to lockedTag
            } else {
                ensureAvailable(localDir, dep)
            }
            if (revParse(localDir, "HEAD") != commit) {
                saveLocalChanges(localDir, commit)
                // --force + clean: files Klone injected must never block switching refs
                git(localDir, "checkout", "--force", "--detach", commit)
                git(localDir, "clean", "-fdq")
            }
            updateSubmodules(localDir, dep.url)
            markUsed(localDir)
            ResolvedDependency(localDir, commit, tag)
        }
    }

    // ── remote queries (kloneOutdated) ──────────────────────────────────────

    /** Tag names on the remote. */
    fun remoteTags(url: String): List<String> =
        git(cacheDir.apply { mkdirs() }, "ls-remote", "--tags", "--refs", url).lineSequence()
            .mapNotNull { it.substringAfter("refs/tags/", "").ifEmpty { null } }
            .toList()

    /** Commit the remote branch (or HEAD) points to. */
    fun remoteHead(url: String, branch: String?): String? =
        git(cacheDir.apply { mkdirs() }, "ls-remote", url, branch?.let { "refs/heads/$it" } ?: "HEAD")
            .lineSequence().firstOrNull()?.substringBefore('\t')?.takeIf { it.isNotBlank() }

    // ── fetching ────────────────────────────────────────────────────────────

    /** Makes the declared ref available locally; returns its commit and, for tags, the tag name. */
    private fun ensureAvailable(dir: File, dep: GitDependency): Pair<String, String?> = when (val ref = dep.ref) {
        is GitRef.Tag -> ensureTag(dir, dep.url, ref, tagCandidates(ref.name, dep.tagPattern))
        is GitRef.Range -> {
            val tag = bestTag(dir, dep, ref) ?: throw KloneGitException(
                "No tag of ${dep.url} matches ${ref.describe()}" + (dep.tagPattern?.let { " (tagPattern \"$it\")" } ?: "")
            )
            ensureTag(dir, dep.url, ref, listOf(tag))
        }
        is GitRef.Branch -> {
            val local = "refs/remotes/origin/${ref.name}"
            fetchOrReuse(dir, dep.url, ref, local, "+refs/heads/${ref.name}:$local") to null
        }
        GitRef.Default -> {
            val local = "refs/klone/default"
            fetchOrReuse(dir, dep.url, ref, local, "+HEAD:$local") to null
        }
        is GitRef.Commit -> ensureCommit(dir, dep.url, ref) to null
    }

    /** "1.2.0" with pattern "sdk-{version}" → sdk-1.2.0, 1.2.0, v1.2.0 (catalog versions rarely match tags 1:1). */
    private fun tagCandidates(name: String, pattern: String?): List<String> =
        listOfNotNull(pattern?.let { SemVer.toTag(name, it) }, name, if (name.startsWith("v")) null else "v$name").distinct()

    private fun ensureTag(dir: File, url: String, ref: GitRef, candidates: List<String>): Pair<String, String> {
        var lastError: Throwable? = null
        for (tag in candidates) {
            val local = "refs/tags/$tag"
            val cached = revParse(dir, local)
            // Tags are immutable: no network once cached (unless --refresh-dependencies)
            if (cached != null && !(refresh && !offline)) return cached to tag
            val fetched = runCatching { fetch(dir, url, ref, "+$local:$local") }
                .onFailure { lastError = it }
                .getOrNull()?.let { revParse(dir, local) }
            if (fetched != null) {
                if (cached != null && cached != fetched) {
                    logger.warn("[Klone] Tag $tag of $url moved from ${cached.take(8)} to ${fetched.take(8)}")
                }
                return fetched to tag
            }
            if (cached != null) return cached to tag
        }
        throw KloneGitException(
            "${ref.describe()} not found in $url (tried ${candidates.joinToString()})" +
                (lastError?.message?.let { "\n$it" } ?: "")
        )
    }

    private fun bestTag(dir: File, dep: GitDependency, ref: GitRef.Range): String? {
        val tags = if (offline) git(dir, "tag", "-l").lines().filter { it.isNotBlank() } else remoteTags(dep.url)
        val versions = tags.mapNotNull { tag -> SemVer.fromTag(tag, dep.tagPattern)?.let { tag to it } }.toMap()
        return try {
            SemVer.best(ref.spec, versions)
        } catch (e: IllegalArgumentException) {
            throw KloneGitException(e.message ?: "Invalid version range ${ref.spec}")
        }
    }

    private fun ensureCommit(dir: File, url: String, ref: GitRef.Commit): String =
        revParse(dir, ref.hash) ?: run {
            // Servers accept a full sha directly; short hashes need the history
            val direct = ref.hash.length == 40 && runCatching { fetch(dir, url, ref, ref.hash) }.isSuccess
            if (!direct) fetchAll(dir, url, ref)
            revParse(dir, ref.hash) ?: throw KloneGitException("${ref.describe()} not found in $url")
        }

    /** Moving refs: always try to update, fall back to the cached copy when the network fails. */
    private fun fetchOrReuse(dir: File, url: String, ref: GitRef, localRef: String, refspec: String): String {
        val cached = revParse(dir, localRef)
        if (offline) return cached ?: throw KloneGitException("${ref.describe()} of $url is not cached and Gradle runs with --offline.")
        try {
            fetch(dir, url, ref, refspec)
        } catch (e: KloneGitException) {
            if (cached == null) throw e
            logger.warn("[Klone] Could not update $url (${ref.describe()}), using cached ${cached.take(8)}: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        return revParse(dir, localRef) ?: throw KloneGitException("${ref.describe()} not found in $url")
    }

    private fun fetch(dir: File, url: String, ref: GitRef, vararg refspecs: String) {
        if (offline) throw KloneGitException("$url (${ref.describe()}) is not cached and Gradle runs with --offline.")
        git(dir, "fetch", "--depth", "1", "--no-tags", "origin", *refspecs)
    }

    private fun fetchAll(dir: File, url: String, ref: GitRef) {
        if (offline) throw KloneGitException("$url (${ref.describe()}) is not cached and Gradle runs with --offline.")
        val shallow = git(dir, "rev-parse", "--is-shallow-repository").trim() == "true"
        val args = mutableListOf("fetch", "--no-tags")
        if (shallow) args += "--unshallow"
        args += listOf("origin", "+refs/heads/*:refs/remotes/origin/*", "+refs/tags/*:refs/tags/*")
        git(dir, *args.toTypedArray())
    }

    // ── working tree ────────────────────────────────────────────────────────

    /**
     * Edits made inside the cache (debugging a library in the IDE) are stashed, never lost,
     * before switching commits. Files Klone itself writes and build outputs are ignored.
     */
    private fun saveLocalChanges(dir: File, target: String) {
        if (revParse(dir, "HEAD") == null) return
        val managed = setOf("gradle.properties", "local.properties", "settings.gradle", "settings.gradle.kts")
        val ignoredDirs = listOf(".gradle/", "build/", ".kotlin/", ".idea/", ".cxx/")
        val changes = git(dir, "status", "--porcelain").lines()
            .filter { it.isNotBlank() }
            .map { it.substring(3).trim('"') }
            .filterNot { path -> path.substringAfterLast('/') in managed || ignoredDirs.any { path.startsWith(it) || path.contains("/$it") } }
        if (changes.isEmpty()) return
        git(dir, "-c", "user.name=Klone", "-c", "user.email=klone@localhost",
            "stash", "push", "--include-untracked", "-m", "klone: local changes before switching to ${target.take(8)}")
        logger.warn(
            "[Klone] Saved local edits in ${dir.absolutePath} (${changes.take(5).joinToString()}${if (changes.size > 5) ", …" else ""}).\n" +
                "Recover them with: git -C \"${dir.absolutePath}\" stash pop — to edit a library permanently use a `path` dependency."
        )
    }

    private fun updateSubmodules(dir: File, url: String) {
        if (!File(dir, ".gitmodules").exists()) return
        if (offline) return
        // Local (file://) submodules are blocked by default since git 2.38; allow them for local remotes only
        val allowFile = if (url.startsWith("file://") || url.startsWith("/")) listOf("-c", "protocol.file.allow=always") else emptyList()
        try {
            git(dir, *(allowFile + listOf("submodule", "update", "--init", "--recursive", "--depth", "1")).toTypedArray())
        } catch (e: KloneGitException) {
            // Submodules are often test fixtures (or use dead git:// URLs); the build may not need them
            logger.warn("[Klone] Could not update submodules of $url — continuing without them: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }}")
        }
        // Tried for this commit: later builds take the fast path instead of retrying (network) every time
        checkedOutCommit(dir)?.let { File(dir, ".git/klone-submodules").writeText(it) }
    }

    // ── repository setup ────────────────────────────────────────────────────

    /** init + remote add in a temp dir, then rename: an interrupted clone never looks valid. */
    private fun init(url: String, targetDir: File) {
        targetDir.parentFile.mkdirs()
        val tmp = File(targetDir.parentFile, ".${targetDir.name}.tmp-${UUID.randomUUID()}")
        try {
            tmp.mkdirs()
            git(tmp, "init", "-q")
            git(tmp, "remote", "add", "origin", url)
            if (!tmp.renameTo(targetDir)) throw IOException("Could not move $tmp to $targetDir")
        } finally {
            tmp.deleteTree()
        }
    }

    /** Commit of a detached HEAD, read from disk (Klone always checks out detached). */
    private fun checkedOutCommit(dir: File): String? =
        runCatching { File(dir, ".git/HEAD").readText().trim() }.getOrNull()?.takeIf { SHA.matches(it) }

    private fun submodulesReady(dir: File): Boolean =
        !File(dir, ".gitmodules").exists() ||
            runCatching { File(dir, ".git/klone-submodules").readText() }.getOrNull() == checkedOutCommit(dir)

    /** Read by `kloneClean --unused-days`. */
    private fun markUsed(dir: File) {
        File(dir, ".git/klone-last-used").writeText(System.currentTimeMillis().toString())
    }

    private fun isValidRepo(dir: File): Boolean =
        File(dir, ".git").isDirectory && runCatching { git(dir, "rev-parse", "--git-dir") }.isSuccess

    private fun revParse(dir: File, rev: String): String? =
        runCatching { git(dir, "rev-parse", "--verify", "--quiet", "$rev^{commit}").trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    // ── locking ─────────────────────────────────────────────────────────────

    /** Serializes access per repo: across threads (same daemon) and processes (parallel builds / CI). */
    private fun <T> withCacheLock(repoDir: File, block: () -> T): T {
        repoDir.parentFile.mkdirs()
        val lockFile = File(repoDir.parentFile, "${repoDir.name}.lock")
        return threadLocks.getOrPut(lockFile.absolutePath) { ReentrantLock() }.withLock {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    // ── process ─────────────────────────────────────────────────────────────

    private fun git(workDir: File, vararg args: String): String {
        // Android repos often exceed Windows' 260-char path limit once nested in the cache
        val command = listOf("git") + (if (isWindows) listOf("-c", "core.longpaths=true") else emptyList()) + args
        val process = try {
            ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    // Never block the build waiting for a username/password prompt
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment()["GCM_INTERACTIVE"] = "never"
                }
                .start()
        } catch (e: IOException) {
            throw KloneGitException("Could not run git — is it installed and on PATH? (${e.message})")
        }

        val output = StringBuilder()
        val reader = thread(isDaemon = true) {
            process.inputStream.bufferedReader().use { output.append(it.readText()) }
        }
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw KloneGitException("Git command timed out after $timeoutMinutes min: ${command.joinToString(" ")}")
        }
        reader.join(TimeUnit.SECONDS.toMillis(10))

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw KloneGitException("Git command failed (exit $exitCode): ${command.joinToString(" ")}\n$output")
        }
        return output.toString()
    }

    private companion object {
        val threadLocks = ConcurrentHashMap<String, ReentrantLock>()
        val SHA = Regex("[0-9a-f]{40}")
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}

class KloneGitException(message: String) : RuntimeException(message)
