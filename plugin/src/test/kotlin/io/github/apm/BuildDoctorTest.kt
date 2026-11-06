package dev.klone

import dev.klone.resolver.BuildDoctor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BuildDoctorTest {

    @TempDir
    lateinit var dir: File

    private fun repo(name: String, agp: String = "9.0.1", files: Map<String, String> = emptyMap()): File {
        val root = File(dir, name)
        (mapOf(
            "gradle/libs.versions.toml" to """
                [versions]
                agp = "$agp"
                kotlin = "2.3.10"
                [plugins]
                android-library = { id = "com.android.library", version.ref = "agp" }
                kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
            """.trimIndent(),
            "settings.gradle.kts" to "rootProject.name = \"$name\"\ninclude(\":core\", \":sample\")\n",
            "build.gradle.kts" to "plugins {\n  alias(libs.plugins.android.library) apply false\n  alias(libs.plugins.kotlin.compose) apply false\n}\n",
            "core/build.gradle.kts" to "plugins { id(\"com.android.library\") }\n",
            "sample/build.gradle.kts" to "plugins { id(\"com.android.library\") }\nandroid { buildFeatures { dataBinding = true } }\n"
        ) + files).forEach { (path, content) -> File(root, path).apply { parentFile.mkdirs(); writeText(content) } }
        return root
    }

    @Test
    fun `standardized repos share a plugin classpath and need no changes`() {
        val a = BuildDoctor.analyze(repo("a"), "a")
        val b = BuildDoctor.analyze(repo("b"), "b")
        assertEquals(setOf("com.android.library:9.0.1", "org.jetbrains.kotlin.plugin.compose:2.3.10"), a.plugins)
        assertEquals(a.classpathKey, b.classpathKey)

        val report = BuildDoctor.render(a.copy(name = "host"), listOf(a, b), null)
        assertTrue(report.contains("2 cloned repo(s), 1 distinct plugin classpath(s)"), report)
        assertTrue(report.contains("Sharing a plugin classpath: a, b"), report)
    }

    @Test
    fun `own build logic, other versions and root logic are reported`() {
        val host = BuildDoctor.analyze(repo("host"), "host")
        val logic = BuildDoctor.analyze(repo("logic", files = mapOf("buildSrc/build.gradle.kts" to "")), "logic")
        val old = BuildDoctor.analyze(repo("old", agp = "8.13.2", files = mapOf(
            "build.gradle.kts" to "plugins { alias(libs.plugins.android.library) apply false }\nsubprojects { }\n"
        )), "old")
        assertEquals(listOf("buildSrc"), logic.ownBuildLogic)
        assertNotEquals(logic.classpathKey, host.classpathKey)
        assertEquals(listOf("subprojects"), old.rootLogic)

        val report = BuildDoctor.render(host, listOf(logic, old), 300L * 1024 * 1024 to 512L * 1024 * 1024)
        assertTrue(report.contains("8.13.2*"), report)
        assertTrue(report.contains("bring their own build logic (logic)"), report)
        assertTrue(report.contains("Align AGP/Kotlin with the host in: old"), report)
        assertTrue(report.contains("Root-level subprojects blocks in old"), report)
        assertTrue(report.contains("Metaspace now: 300 MB of 512 MB"), report)
    }

    @Test
    fun `hygiene only counts modules Klone keeps`() {
        val root = repo("trimmed")
        assertEquals(listOf("Data Binding"), BuildDoctor.analyze(root, "trimmed").hygiene)

        // Klone configures :sample empty: its Data Binding never runs
        File(root, "settings.gradle.kts").appendText("// <klone-modules>\nlistOf(\":sample\").forEach { }\n// </klone-modules>\n")
        val report = BuildDoctor.analyze(root, "trimmed")
        assertTrue(report.hygiene.isEmpty())
        assertEquals(1, report.modulesUsed) // :core
        assertEquals(2, report.modulesTotal)
    }
}
