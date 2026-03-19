package dev.klone

import dev.klone.resolver.ModuleDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalDependencyIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `ModuleDetector detects modules from local path`() {
        val libDir = File(tempDir, "mylib").also { it.mkdirs() }
        File(libDir, "build.gradle.kts").writeText("""
            group = "com.example"
        """.trimIndent())
        File(libDir, "settings.gradle.kts").writeText("""
            rootProject.name = "mylib"
        """.trimIndent())

        val submodules = ModuleDetector.detectAllSubmodules(libDir)

        assertEquals(1, submodules.size)
        assertEquals("com.example:mylib", submodules[0].coordinates)
        assertEquals(":", submodules[0].projectPath)
    }

    @Test
    fun `resolveLocalPath resolves relative path against root`() {
        val rootDir = File(tempDir, "project").also { it.mkdirs() }
        val libDir = File(tempDir, "mylib").also { it.mkdirs() }
        File(libDir, "build.gradle.kts").writeText("group = \"com.example\"")

        val resolved = resolveLocalPath("../mylib", rootDir)

        assertEquals(libDir.canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun `resolveLocalPath handles absolute path`() {
        val absPath = File(tempDir, "absolute-lib").also { it.mkdirs() }

        val resolved = resolveLocalPath(absPath.absolutePath, tempDir)

        assertEquals(absPath.absolutePath, resolved.absolutePath)
    }

    @Test
    fun `hasBuildFile returns true when build file exists`() {
        val libDir = File(tempDir, "lib").also { it.mkdirs() }
        File(libDir, "build.gradle.kts").writeText("")

        assertTrue(hasBuildFile(libDir))
    }

    @Test
    fun `hasBuildFile returns false when no build file exists`() {
        val libDir = File(tempDir, "empty-lib").also { it.mkdirs() }

        assertTrue(!hasBuildFile(libDir))
    }

    private fun resolveLocalPath(path: String, rootDir: File): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(rootDir, path).canonicalFile
    }

    private fun hasBuildFile(dir: File): Boolean =
        File(dir, "build.gradle.kts").exists() || File(dir, "build.gradle").exists()
}
