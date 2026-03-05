package dev.klone

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitDependencyTest {

    @Test
    fun `repoName extracts last path segment`() {
        val dep = GitDependency("https://github.com/square/retrofit", GitRef.Branch("main"))
        assertEquals("retrofit", dep.repoName)
    }

    @Test
    fun `repoName strips dot git suffix`() {
        val dep = GitDependency("https://github.com/square/retrofit.git", GitRef.Branch("main"))
        assertEquals("retrofit", dep.repoName)
    }

    @Test
    fun `cacheKey encodes host and path`() {
        val dep = GitDependency("https://github.com/square/retrofit", GitRef.Tag("2.9.0"))
        assertEquals("github.com/square/retrofit", dep.cacheKey)
    }

    @Test
    fun `cacheKey strips dot git`() {
        val dep = GitDependency("https://github.com/user/mylib.git", GitRef.Branch("main"))
        assertEquals("github.com/user/mylib", dep.cacheKey)
    }
}
