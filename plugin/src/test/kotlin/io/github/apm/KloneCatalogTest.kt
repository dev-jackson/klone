package dev.klone

import dev.klone.model.GitRef
import dev.klone.toml.KloneCatalog
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KloneCatalogTest {

    @TempDir
    lateinit var tempDir: File

    private fun catalog(content: String) = File(tempDir, "libs.versions.toml").also { it.writeText(content.trimIndent()) }

    @Test
    fun `missing file or metadata yields empty catalog`() {
        assertTrue(KloneCatalog.parse(File(tempDir, "none.toml")).gitDependencies.isEmpty())
        val parsed = KloneCatalog.parse(catalog("""
            [libraries]
            a = { module = "g:a", version = "1" }
        """))
        assertTrue(parsed.gitDependencies.isEmpty() && parsed.localDependencies.isEmpty())
    }

    @Test
    fun `reads git and local sources from metadata`() {
        val parsed = KloneCatalog.parse(catalog("""
            [versions]
            retrofit = "2.9.0"

            [libraries]
            retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
            gson = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
            design = "com.org:design"
            pinned = { module = "com.org:pinned", version = "3.0" }
            utils = { module = "com.org:utils" }

            [metadata]
            format.version = "1.1"
            klone.retrofit = "https://github.com/square/retrofit"
            klone.gson = { git = "https://github.com/square/retrofit", module = "converter-gson" }
            klone.design = { git = "git@github.com:org/ds.git", branch = "main" }
            klone.pinned = { git = "https://github.com/org/pinned", tag = "release-3.0" }
            klone.utils = { path = "../utils" }
        """))

        val git = parsed.gitDependencies.associateBy { it.moduleCoordinates }
        assertEquals(GitRef.Tag("2.9.0"), git["com.squareup.retrofit2:retrofit"]?.ref)
        assertEquals(listOf("converter-gson"), git["com.squareup.retrofit2:converter-gson"]?.submoduleNames)
        assertEquals(GitRef.Branch("main"), git["com.org:design"]?.ref)
        assertEquals(GitRef.Tag("release-3.0"), git["com.org:pinned"]?.ref)
        assertEquals("../utils", parsed.localDependencies.single().path)
        assertEquals("com.org:utils", parsed.localDependencies.single().moduleCoordinates)
    }

    @Test
    fun `library without version follows default branch`() {
        val parsed = KloneCatalog.parse(catalog("""
            [libraries]
            lib = { module = "g:lib" }
            [metadata]
            klone.lib = "https://github.com/g/lib"
        """))
        assertEquals(GitRef.Default, parsed.gitDependencies.single().ref)
    }

    @Test
    fun `clear errors for invalid entries`() {
        val unknownAlias = assertThrows<GradleException> {
            KloneCatalog.parse(catalog("""
                [metadata]
                klone.nope = "https://github.com/g/x"
            """))
        }
        assertTrue(unknownAlias.message!!.contains("no library 'nope'"))

        val badKey = assertThrows<GradleException> {
            KloneCatalog.parse(catalog("""
                [libraries]
                x = { module = "g:x" }
                [metadata]
                klone.x = { git = "https://github.com/g/x", tags = "1" }
            """))
        }
        assertTrue(badKey.message!!.contains("unknown keys [tags]"))

        val twoRefs = assertThrows<GradleException> {
            KloneCatalog.parse(catalog("""
                [libraries]
                x = { module = "g:x" }
                [metadata]
                klone.x = { git = "https://github.com/g/x", tag = "1", branch = "main" }
            """))
        }
        assertTrue(twoRefs.message!!.contains("Use only one of"))
    }
}
