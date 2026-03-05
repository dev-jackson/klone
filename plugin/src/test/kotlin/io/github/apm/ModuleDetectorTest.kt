package dev.klone

import dev.klone.resolver.ModuleDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModuleDetectorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `detects group and artifactId from kts build file`() {
        File(tempDir, "build.gradle.kts").writeText("""
            group = "com.squareup.retrofit2"
            version = "2.9.0"
        """.trimIndent())
        File(tempDir, "settings.gradle.kts").writeText("""
            rootProject.name = "retrofit"
        """.trimIndent())

        val result = ModuleDetector.detect(tempDir, "retrofit")

        assertEquals("com.squareup.retrofit2:retrofit", result)
    }

    @Test
    fun `detects group from groovy build file`() {
        File(tempDir, "build.gradle").writeText("""
            group = 'com.example.library'
            version = '1.0.0'
        """.trimIndent())
        File(tempDir, "settings.gradle").writeText("""
            rootProject.name = 'mylib'
        """.trimIndent())

        val result = ModuleDetector.detect(tempDir, "mylib")

        assertEquals("com.example.library:mylib", result)
    }

    @Test
    fun `falls back to repoName when settings missing`() {
        File(tempDir, "build.gradle.kts").writeText("""
            group = "com.example"
        """.trimIndent())

        val result = ModuleDetector.detect(tempDir, "fallback-name")

        assertEquals("com.example:fallback-name", result)
    }

    @Test
    fun `returns null when no group found`() {
        File(tempDir, "build.gradle.kts").writeText("""
            version = "1.0.0"
        """.trimIndent())

        val result = ModuleDetector.detect(tempDir, "somelib")

        assertNull(result)
    }

    @Test
    fun `detectAllSubmodules returns root for repo with no includes`() {
        File(tempDir, "build.gradle.kts").writeText("""
            group = "com.example"
            version = "1.0.0"
        """.trimIndent())
        File(tempDir, "settings.gradle.kts").writeText("""
            rootProject.name = "mylib"
        """.trimIndent())

        val result = ModuleDetector.detectAllSubmodules(tempDir)

        assertEquals(1, result.size)
        assertEquals(":", result[0].projectPath)
        assertEquals("com.example:mylib", result[0].coordinates)
        assertNull(result[0].moduleName)
    }

    @Test
    fun `detectAllSubmodules detects submodules from settings includes`() {
        File(tempDir, "build.gradle.kts").writeText("""
            group = "com.test"
            version = "1.0.0"
        """.trimIndent())
        File(tempDir, "settings.gradle.kts").writeText("""
            rootProject.name = "test-multimodule"
            include(":core")
            include(":extras")
        """.trimIndent())

        val coreDir = File(tempDir, "core").also { it.mkdirs() }
        File(coreDir, "build.gradle.kts").writeText("""
            group = "com.test"
        """.trimIndent())

        val extrasDir = File(tempDir, "extras").also { it.mkdirs() }
        File(extrasDir, "build.gradle.kts").writeText("""
            group = "com.test"
        """.trimIndent())

        val result = ModuleDetector.detectAllSubmodules(tempDir)

        assertEquals(2, result.size)
        val byName = result.associateBy { it.moduleName }
        assertEquals("com.test:core", byName["core"]?.coordinates)
        assertEquals(":core", byName["core"]?.projectPath)
        assertEquals("com.test:extras", byName["extras"]?.coordinates)
        assertEquals(":extras", byName["extras"]?.projectPath)
    }

    @Test
    fun `parseSettingsIncludes handles multi-arg include`() {
        File(tempDir, "build.gradle.kts").writeText("""group = "com.test"""")
        File(tempDir, "settings.gradle").writeText("""
            include ':retrofit', ':converter-gson', ':adapter-rxjava2'
        """.trimIndent())

        listOf("retrofit", "converter-gson", "adapter-rxjava2").forEach {
            File(tempDir, it).mkdirs()
            File(tempDir, "$it/build.gradle.kts").writeText("""group = "com.test"""")
        }

        val result = ModuleDetector.detectAllSubmodules(tempDir)

        assertEquals(3, result.size)
        assertEquals(setOf("retrofit", "converter-gson", "adapter-rxjava2"), result.map { it.moduleName }.toSet())
    }

    @Test
    fun `parseSettingsIncludes handles Groovy without parens`() {
        File(tempDir, "build.gradle.kts").writeText("""group = "com.test"""")
        File(tempDir, "settings.gradle").writeText("include ':mylib'")

        File(tempDir, "mylib").mkdirs()
        File(tempDir, "mylib/build.gradle.kts").writeText("""group = "com.test"""")

        val result = ModuleDetector.detectAllSubmodules(tempDir)

        assertEquals(1, result.size)
        assertEquals("mylib", result[0].moduleName)
    }

    @Test
    fun `detectGroup finds group in allprojects block`() {
        File(tempDir, "build.gradle").writeText("""
            allprojects {
                group = 'com.squareup.retrofit2'
                version = '2.9.0'
            }
        """.trimIndent())
        File(tempDir, "settings.gradle").writeText("rootProject.name = 'retrofit'")

        val result = ModuleDetector.detect(tempDir, "retrofit")

        assertEquals("com.squareup.retrofit2:retrofit", result)
    }

    @Test
    fun `detectGroup reads gradle properties`() {
        File(tempDir, "gradle.properties").writeText("group=com.squareup.okhttp3\nversion=4.12.0")
        File(tempDir, "settings.gradle.kts").writeText("""rootProject.name = "okhttp"""")

        val result = ModuleDetector.detect(tempDir, "okhttp")

        assertEquals("com.squareup.okhttp3:okhttp", result)
    }

    @Test
    fun `detectAllSubmodules falls back to root group when submodule has no group`() {
        File(tempDir, "build.gradle.kts").writeText("""
            group = "com.fallback"
        """.trimIndent())
        File(tempDir, "settings.gradle.kts").writeText("""
            include(":module1")
        """.trimIndent())

        val moduleDir = File(tempDir, "module1").also { it.mkdirs() }
        // No build.gradle.kts in moduleDir — falls back to root group
        File(moduleDir, "build.gradle.kts").writeText("// empty")

        val result = ModuleDetector.detectAllSubmodules(tempDir)

        assertEquals(1, result.size)
        assertEquals("com.fallback:module1", result[0].coordinates)
    }
}
