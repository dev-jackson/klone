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

    // --- localImplementation() scanning tests ---

    @Test
    fun `detects localImplementation with absolute path`() {
        val file = buildFile("""
            dependencies {
                localImplementation("/absolute/path/to/lib")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFileForLocal(file)

        assertEquals(1, deps.size)
        assertEquals("/absolute/path/to/lib", deps[0].path)
        assertEquals(null, deps[0].moduleCoordinates)
        assertEquals(null, deps[0].submoduleNames)
    }

    @Test
    fun `detects localImplementation with relative path`() {
        val file = buildFile("""
            dependencies {
                localImplementation("../my-library")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFileForLocal(file)

        assertEquals(1, deps.size)
        assertEquals("../my-library", deps[0].path)
    }

    @Test
    fun `detects localImplementation with module param`() {
        val file = buildFile("""
            dependencies {
                localImplementation("/path/to/lib", module = "core")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanFileForLocal(file)

        assertEquals(1, deps.size)
        assertEquals("/path/to/lib", deps[0].path)
        assertEquals("core", deps[0].moduleCoordinates)
    }

    @Test
    fun `detects mix of gitImplementation and localImplementation`() {
        val file = buildFile("""
            dependencies {
                gitImplementation("https://github.com/square/retrofit", from = "2.9.0")
                localImplementation("../my-library")
                implementation("androidx.core:core-ktx:1.12.0")
            }
        """.trimIndent())

        val gitDeps = BuildFileScanner.scanFile(file)
        val localDeps = BuildFileScanner.scanFileForLocal(file)

        assertEquals(1, gitDeps.size)
        assertEquals(1, localDeps.size)
        assertEquals("../my-library", localDeps[0].path)
    }

    @Test
    fun `scanProjectForLocal walks directories`() {
        val subDir = File(tempDir, "app").also { it.mkdirs() }
        File(subDir, "build.gradle.kts").writeText("""
            dependencies {
                localImplementation("../some-lib")
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanProjectForLocal(tempDir)

        assertEquals(1, deps.size)
        assertEquals("../some-lib", deps[0].path)
    }

    private fun buildFile(content: String): File {
        return File(tempDir, "build.gradle.kts").also { it.writeText(content) }
    }
}
