package dev.klone.scenarios

import dev.klone.fixtures.Scenario
import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.SemVer
import dev.klone.resolver.GitResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Against real hosts instead of `file://` remotes. Opt-in (network, rate limits):
 *   KLONE_REAL_REMOTE=true ./gradlew :plugin:functionalTest --tests '*RealRemote*'
 * Private repo over SSH, additionally:
 *   KLONE_TEST_SSH_URL=git@github.com:your-org/your-lib.git [KLONE_TEST_SSH_TAG=1.0.0]
 * Expectations are computed from `git ls-remote`, so new upstream releases never break them.
 */
class RealRemoteScenario : Scenario() {

    private val publicUrl = "https://github.com/square/retrofit"

    @BeforeEach
    fun optIn() = assumeTrue(System.getenv("KLONE_REAL_REMOTE") == "true", "set KLONE_REAL_REMOTE=true to run")

    private fun lsRemote(vararg args: String): List<Pair<String, String>> {
        val p = ProcessBuilder(listOf("git", "ls-remote") + args).redirectErrorStream(true)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
        return out.lines().filter { it.contains('\t') }.map { it.substringAfter('\t') to it.substringBefore('\t') }
    }

    /** Commit a tag points to (peeled for annotated tags). */
    private fun tagCommit(url: String, tag: String): String {
        val refs = lsRemote(url, "refs/tags/$tag", "refs/tags/$tag^{}").toMap()
        return refs["refs/tags/$tag^{}"] ?: refs.getValue("refs/tags/$tag")
    }

    @Test
    fun `public https repo - exact, SPM from, default branch, commit by sha`() {
        val resolver = GitResolver(cacheDir)

        val exact = resolver.resolve(GitDependency(publicUrl, GitRef.Tag("2.9.0")))
        assertEquals(tagCommit(publicUrl, "2.9.0"), exact.commitHash)
        assertTrue(File(exact.localDir, "settings.gradle").exists() || File(exact.localDir, "settings.gradle.kts").exists())

        val tags = resolver.remoteTags(publicUrl)
        val expected = SemVer.best(SemVer.upToNextMajor("2.9.0"), tags.mapNotNull { t -> SemVer.parse(t)?.let { t to it } }.toMap())!!
        val ranged = resolver.resolve(GitDependency(publicUrl, GitRef.upToNextMajor("2.9.0")))
        assertEquals(expected, ranged.tag)
        assertEquals(tagCommit(publicUrl, expected), ranged.commitHash)

        val head = lsRemote(publicUrl, "HEAD").single().second
        assertEquals(head, resolver.resolve(GitDependency(publicUrl)).commitHash)

        // GitHub serves a full sha that is not in the shallow cache
        val fresh = GitResolver(File(tempDir, "fresh-cache"))
        assertEquals(exact.commitHash, fresh.resolve(GitDependency(publicUrl, GitRef.Commit(exact.commitHash))).commitHash)
    }

    @Test
    fun `public https repo in a real host build`() {
        val host = host(files = mapOf(
            "settings.gradle.kts" to """
                plugins { id("io.github.dev-jackson.klone") }
                rootProject.name = "host"
                include(":app")
            """.trimIndent(),
            "app/build.gradle.kts" to """
                plugins { `java-library`; id("io.github.dev-jackson.klone.project") }
                dependencies { gitImplementation("$publicUrl", exact = "2.9.0", module = "retrofit") }
            """.trimIndent()
        ))
        val output = host.build("kloneList", "-q").output
        assertTrue(output.contains("$publicUrl  [tag: 2.9.0]  2.9.0 @ ${tagCommit(publicUrl, "2.9.0").take(10)}"), output)
        assertTrue(host.lockFile.readText().contains("\"resolvedTag\": \"2.9.0\""))
    }

    @Test
    fun `private repo over ssh`() {
        // CI passes unset secrets as empty strings
        val url = System.getenv("KLONE_TEST_SSH_URL")?.takeIf { it.isNotBlank() }
        assumeTrue(url != null, "set KLONE_TEST_SSH_URL=git@host:org/private-lib.git to run")
        val tag = System.getenv("KLONE_TEST_SSH_TAG")?.takeIf { it.isNotBlank() }
        val ref = tag?.let { GitRef.Tag(it) } ?: GitRef.Default
        val resolved = GitResolver(cacheDir).resolve(GitDependency(url!!, ref))
        val expected = if (tag != null) tagCommit(url, tag) else lsRemote(url, "HEAD").single().second
        assertEquals(expected, resolved.commitHash)
    }
}
