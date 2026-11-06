package dev.klone

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.SemVer
import dev.klone.resolver.VersionSolver
import dev.klone.resolver.VersionSolver.Requirement
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VersionSolverTest {

    private val url = "https://github.com/org/sdk"
    private fun req(ref: GitRef, by: String, pattern: String? = null) = Requirement(GitDependency(url, ref, tagPattern = pattern), by)

    @Test
    fun `identical refs stay as declared`() {
        val dep = VersionSolver.combine(listOf(req(GitRef.Branch("main"), "host"), req(GitRef.Branch("main"), "feature")))
        assertEquals(GitRef.Branch("main"), dep.ref)
    }

    @Test
    fun `exact versions and ranges intersect into one range`() {
        val dep = VersionSolver.combine(listOf(req(GitRef.Tag("1.2.0"), "host"), req(GitRef.upToNextMajor("1.0.0"), "feature")))
        assertEquals(GitRef.Range("=1.2.0 && >=1.0.0 <2.0.0"), dep.ref)
        val candidates = listOf("1.0.0", "1.2.0", "1.3.0").associateWith { SemVer.parse(it)!! }
        assertEquals("1.2.0", SemVer.best((dep.ref as GitRef.Range).spec, candidates))
    }

    @Test
    fun `intersection without a common version selects nothing`() {
        val spec = (VersionSolver.combine(listOf(req(GitRef.Tag("1.0.0"), "host"), req(GitRef.upToNextMajor("1.2.0"), "feature"))).ref as GitRef.Range).spec
        val candidates = listOf("1.0.0", "1.2.0").associateWith { SemVer.parse(it)!! }
        assertEquals(null, SemVer.best(spec, candidates))
        val message = VersionSolver.noVersion(url, listOf(req(GitRef.Tag("1.0.0"), "host"), req(GitRef.upToNextMajor("1.2.0"), "feature")), null).message!!
        assertTrue(message.contains("host: tag: 1.0.0") && message.contains("feature: version: >=1.2.0 <2.0.0"), message)
    }

    @Test
    fun `branches, commits and different patterns conflict`() {
        assertThrows<GradleException> { VersionSolver.combine(listOf(req(GitRef.Branch("main"), "host"), req(GitRef.Branch("dev"), "feature"))) }
        assertThrows<GradleException> { VersionSolver.combine(listOf(req(GitRef.Branch("main"), "host"), req(GitRef.Tag("1.0.0"), "feature"))) }
        assertThrows<GradleException> { VersionSolver.combine(listOf(req(GitRef.Tag("1.0.0"), "host", "sdk-{version}"), req(GitRef.Tag("1.2.0"), "feature", "v{version}"))) }
    }

    @Test
    fun `combined ranges are ranges`() {
        assertTrue(SemVer.isRange(">=1.0.0 && <2.0.0"))
        assertFalse(SemVer.matcher("^1.0 && <1.5")(SemVer.parse("1.5.0")!!))
        assertTrue(SemVer.matcher("^1.0 && <1.5")(SemVer.parse("1.4.9")!!))
    }
}
