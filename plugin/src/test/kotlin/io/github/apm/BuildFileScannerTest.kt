package dev.klone

import dev.klone.model.GitRef
import dev.klone.resolver.BuildFileScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BuildFileScannerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `detects gitImplementation with from version`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/square/retrofit", from = "2.9.0")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertEquals(1, deps.size)
        assertEquals("https://github.com/square/retrofit", deps[0].url)
        assertEquals(GitRef.Tag("2.9.0"), deps[0].ref)
    }

    @Test
    fun `detects gitImplementation with branch`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/user/mylib", branch = "main")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertEquals(1, deps.size)
        assertEquals(GitRef.Branch("main"), deps[0].ref)
    }

    @Test
    fun `detects gitImplementation with commit`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/user/lib", commit = "abc123def456")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertEquals(1, deps.size)
        assertEquals(GitRef.Commit("abc123def456"), deps[0].ref)
    }

    @Test
    fun `detects multiple gitImplementation calls`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/square/retrofit", from = "2.9.0")
                gitImplementation("https://github.com/user/mylib", branch = "main")
                implementation("androidx.core:core-ktx:1.12.0")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertEquals(2, deps.size)
    }

    @Test
    fun `ignores regular implementation calls`() {
        val file = buildFile("""
            dependencies {
                implementation("com.example:lib:1.0.0")
                testImplementation("org.junit:junit:4.13")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertTrue(deps.isEmpty())
    }

    @Test
    fun `returns empty list for missing file`() {
        val missing = File(tempDir, "nonexistent.gradle.kts")
        assertTrue(BuildFileScanner.scanFile(missing).isEmpty())
    }

    @Test
    fun `detects gitImplementation with module param`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/square/retrofit", module = "converter-gson", from = "2.9.0")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertEquals(1, deps.size)
        assertEquals("https://github.com/square/retrofit", deps[0].url)
        assertEquals(GitRef.Tag("2.9.0"), deps[0].ref)
        assertEquals(listOf("converter-gson"), deps[0].submoduleNames)
    }

    @Test
    fun `detects gitImplementation with modules listOf`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/square/retrofit",
                    modules = listOf("converter-gson", "adapter-rxjava2"), from = "2.9.0")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertEquals(1, deps.size)
        assertEquals("https://github.com/square/retrofit", deps[0].url)
        assertEquals(GitRef.Tag("2.9.0"), deps[0].ref)
        assertEquals(listOf("converter-gson", "adapter-rxjava2"), deps[0].submoduleNames)
    }

    @Test
    fun `plain gitImplementation has null submoduleNames`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/user/mylib", branch = "main")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFile(file)

        assertEquals(1, deps.size)
        assertEquals(null, deps[0].submoduleNames)
    }

    private fun buildFile(content: String): File {
        return File(tempDir, "build.gradle.kts").also { it.writeText(content) }
    }
}
