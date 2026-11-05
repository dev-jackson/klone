package dev.klone

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.resolver.GitResolver
import dev.klone.resolver.KloneGitException
import dev.klone.resolver.LockFileManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitResolverTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var remote: File
    private lateinit var cache: File
    private val url get() = dev.klone.model.fileUrl(remote)

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    private fun commit(content: String): String {
        File(remote, "VERSION").writeText(content)
        git(remote, "add", ".")
        git(remote, "commit", "-q", "-m", content)
        return git(remote, "rev-parse", "HEAD")
    }

    private fun checkedOut(dir: File) = File(dir, "VERSION").readText()

    @BeforeEach
    fun setUp() {
        remote = File(tempDir, "remote").also { it.mkdirs() }
        cache = File(tempDir, "cache")
        git(remote, "init", "-q", "-b", "main")
        commit("1.0.0"); git(remote, "tag", "1.0.0")
        commit("1.1.0"); git(remote, "tag", "v1.1.0")
    }

    @Test
    fun `switching tags on a warm cache fetches the new tag`() {
        val resolver = GitResolver(cache)
        val first = resolver.resolve(GitDependency(url, GitRef.Tag("1.0.0")))
        assertEquals("1.0.0", checkedOut(first.localDir))

        val second = resolver.resolve(GitDependency(url, GitRef.Tag("1.1.0"))) // only "v1.1.0" exists
        assertEquals("1.1.0", checkedOut(second.localDir))
    }

    @Test
    fun `branch follows remote and locked commit wins`() {
        val resolver = GitResolver(cache)
        val dep = GitDependency(url, GitRef.Branch("main"))
        val pinned = resolver.resolve(dep).commitHash

        commit("2.0.0")
        assertEquals("1.1.0", checkedOut(resolver.resolve(dep, lockedCommit = pinned).localDir))
        val updated = resolver.resolve(dep)
        assertNotEquals(pinned, updated.commitHash)
        assertEquals("2.0.0", checkedOut(updated.localDir))
    }

    @Test
    fun `default ref follows remote HEAD`() {
        val resolved = GitResolver(cache).resolve(GitDependency(url))
        assertEquals("1.1.0", checkedOut(resolved.localDir))
    }

    @Test
    fun `commit not in shallow cache is fetched, short hashes work`() {
        val old = git(remote, "rev-parse", "1.0.0")
        val resolver = GitResolver(cache)
        resolver.resolve(GitDependency(url, GitRef.Branch("main"))) // shallow: only tip
        assertEquals(old, resolver.resolve(GitDependency(url, GitRef.Commit(old.take(8)))).commitHash)
    }

    @Test
    fun `corrupt cache and modified files never block resolution`() {
        val resolver = GitResolver(cache)
        val dir = resolver.resolve(GitDependency(url, GitRef.Tag("1.0.0"))).localDir
        File(dir, "VERSION").writeText("locally modified")
        File(dir, "untracked.txt").writeText("x")
        assertEquals("1.1.0", checkedOut(resolver.resolve(GitDependency(url, GitRef.Tag("v1.1.0"))).localDir))
        assertTrue(!File(dir, "untracked.txt").exists())

        File(dir, ".git").deleteRecursively()
        assertEquals("1.0.0", checkedOut(resolver.resolve(GitDependency(url, GitRef.Tag("1.0.0"))).localDir))
    }

    @Test
    fun `offline uses cache and fails clearly without it`() {
        GitResolver(cache).resolve(GitDependency(url, GitRef.Branch("main")))
        commit("3.0.0")
        val offline = GitResolver(cache, offline = true)
        assertEquals("1.1.0", checkedOut(offline.resolve(GitDependency(url, GitRef.Branch("main"))).localDir))

        val other = File(tempDir, "other").also { it.mkdirs(); git(it, "init", "-q") }
        val e = assertThrows<KloneGitException> { offline.resolve(GitDependency(dev.klone.model.fileUrl(other), GitRef.Tag("1"))) }
        assertTrue(e.message!!.contains("--offline"))
    }

    @Test
    fun `missing repo fails fast without prompting`() {
        val e = assertThrows<KloneGitException> {
            GitResolver(cache).resolve(GitDependency(dev.klone.model.fileUrl(File(tempDir, "nope")), GitRef.Tag("1.0.0")))
        }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    fun `lock file keeps resolvedAt and skips rewrite when unchanged`() {
        val lock = LockFileManager(tempDir)
        val entry = LockFileManager.LockEntry(url, "branch", "main", "abc", "g:a")
        lock.write(listOf(entry))
        val file = File(tempDir, "klone.resolved")
        val firstText = file.readText()
        file.setLastModified(1000)

        val again = LockFileManager(tempDir)
        assertEquals("abc", again.lockedCommit("$url.git", GitRef.Branch("main")))
        assertEquals(null, again.lockedCommit(url, GitRef.Branch("dev")))
        again.write(listOf(entry))
        assertEquals(firstText, file.readText())
        assertEquals(1000, file.lastModified())
    }
}
