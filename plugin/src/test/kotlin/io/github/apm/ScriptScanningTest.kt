package dev.klone

import dev.klone.model.GitRef
import dev.klone.resolver.BuildFileScanner
import dev.klone.resolver.ModuleDetector
import dev.klone.toml.VersionCatalogReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ScriptScanningTest {

    @TempDir
    lateinit var tempDir: File

    private fun buildFile(content: String) = File(tempDir, "build.gradle.kts").also { it.writeText(content.trimIndent()) }

    @Test
    fun `detects multi-line call with listOf and trailing comma`() {
        val deps = BuildFileScanner.scanFile(buildFile("""
            dependencies {
                gitImplementation(
                    "https://github.com/square/retrofit",
                    modules = listOf(
                        "retrofit",
                        "converter-gson",
                    ),
                    from = "2.9.0",
                )
            }
        """))
        assertEquals(1, deps.size)
        assertEquals(GitRef.Range(">=2.9.0 <3.0.0"), deps[0].ref)
        assertEquals(listOf("retrofit", "converter-gson"), deps[0].submoduleNames)
    }

    @Test
    fun `ignores commented-out calls`() {
        val deps = BuildFileScanner.scanFile(buildFile("""
            dependencies {
                // gitImplementation("https://github.com/a/old", from = "1.0")
                /* gitImplementation("https://github.com/a/older", from = "0.1") */
                gitImplementation("https://github.com/a/new", from = "2.0") // trailing comment
            }
        """))
        assertEquals(listOf("https://github.com/a/new"), deps.map { it.url })
    }

    @Test
    fun `call without ref follows default branch`() {
        val deps = BuildFileScanner.scanFile(buildFile("""gitImplementation("https://github.com/a/b")"""))
        assertEquals(GitRef.Default, deps.single().ref)
    }

    @Test
    fun `positional args and groovy named args`() {
        val groovy = File(tempDir, "build.gradle").also {
            it.writeText("""dependencies { gitImplementation('https://github.com/a/b', branch: 'dev') }""")
        }
        assertEquals(GitRef.Branch("dev"), BuildFileScanner.scanFile(groovy).single().ref)
        val kts = buildFile("""gitImplementation("https://github.com/a/c", "1.0.0")""")
        assertEquals(GitRef.Range(">=1.0.0 <2.0.0"), BuildFileScanner.scanFile(kts).single().ref)
    }

    @Test
    fun `resolves libs versions references from version catalog`() {
        File(tempDir, "gradle").mkdirs()
        File(tempDir, "gradle/libs.versions.toml").writeText("""
            [versions]
            retrofit = "2.9.0"
            kotlin-coroutines = { strictly = "1.8.0" }
        """.trimIndent())
        File(tempDir, "app").mkdirs()
        File(tempDir, "app/build.gradle.kts").writeText("""
            dependencies {
                gitImplementation(
                    "https://github.com/square/retrofit",
                    from = libs.versions.retrofit.get()
                )
                gitImplementation("https://github.com/a/co", from = libs.versions.kotlin.coroutines.get())
            }
        """.trimIndent())

        val deps = BuildFileScanner.scanProject(tempDir, VersionCatalogReader.read(tempDir)).associateBy { it.url }
        assertEquals(GitRef.Range(">=2.9.0 <3.0.0"), deps["https://github.com/square/retrofit"]?.ref)
        assertEquals(GitRef.Range(">=1.8.0 <2.0.0"), deps["https://github.com/a/co"]?.ref)
    }

    @Test
    fun `scanProject skips build outputs and nested builds`() {
        File(tempDir, "build/tmp").mkdirs()
        File(tempDir, "build/tmp/build.gradle.kts").writeText("""gitImplementation("https://x/generated", from = "1")""")
        File(tempDir, "nested").mkdirs()
        File(tempDir, "nested/settings.gradle.kts").writeText("")
        File(tempDir, "nested/build.gradle.kts").writeText("""gitImplementation("https://x/nested", from = "1")""")
        File(tempDir, "app").mkdirs()
        File(tempDir, "app/build.gradle.kts").writeText("""gitImplementation("https://x/real", from = "1")""")

        assertEquals(listOf("https://x/real"), BuildFileScanner.scanProject(tempDir).map { it.url })
    }

    @Test
    fun `module detector reads multi-line includes, colonless paths and projectDir remaps`() {
        File(tempDir, "gradle.properties").writeText("group=com.test\n")
        File(tempDir, "settings.gradle.kts").writeText("""
            rootProject.name = "lib"
            include(
                ":core",
                "ext",
            )
            include(":sample")
            include(":remapped")
            project(":remapped").projectDir = file("libs/remapped")
            includeBuild("build-logic")
        """.trimIndent())
        listOf("core", "ext", "libs/remapped").forEach { File(tempDir, it).mkdirs(); File(tempDir, "$it/build.gradle.kts").writeText("") }
        File(tempDir, "sample").mkdirs()
        File(tempDir, "sample/build.gradle.kts").writeText("""plugins { id("com.android.application") }""")

        val result = ModuleDetector.detectAllSubmodules(tempDir)
        assertEquals(listOf(":core", ":ext", ":remapped"), result.map { it.projectPath })
    }

    @Test
    fun `module detector understands vanniktech-style repos like retrofit`() {
        File(tempDir, "gradle.properties").writeText("GROUP=com.squareup.retrofit2\nVERSION_NAME=2.9.0\n")
        File(tempDir, "settings.gradle").writeText("""
            include ':retrofit'
            include ':retrofit:test-helpers'
            include ':retrofit-converters:gson'
            include ':samples'
        """.trimIndent())
        mapOf("retrofit" to "retrofit", "retrofit-converters/gson" to "converter-gson").forEach { (dir, artifact) ->
            File(tempDir, dir).mkdirs()
            File(tempDir, "$dir/build.gradle").writeText("apply plugin: 'java-library'\n")
            File(tempDir, "$dir/gradle.properties").writeText("POM_ARTIFACT_ID=$artifact\n")
        }
        listOf("retrofit/test-helpers", "samples").forEach { File(tempDir, it).mkdirs(); File(tempDir, "$it/build.gradle").writeText("apply plugin: 'java-library'\n") }

        val result = ModuleDetector.detectAllSubmodules(tempDir)
        assertEquals(
            listOf(":retrofit" to "com.squareup.retrofit2:retrofit", ":retrofit-converters:gson" to "com.squareup.retrofit2:converter-gson"),
            result.map { it.projectPath to it.coordinates }
        )
    }
}
