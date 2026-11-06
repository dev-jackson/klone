package dev.klone

import dev.klone.composite.ModuleTrimmer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModuleTrimmerTest {

    @TempDir
    lateinit var dir: File

    private fun write(path: String, content: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(content) }

    private fun repo() {
        write("settings.gradle.kts", """
            include(":app", ":benchmark", ":balloon", ":balloon-compose")
            include(":converters:gson", ":converters:moshi")
        """.trimIndent())
        write("balloon/build.gradle.kts", "plugins { id(\"com.android.library\") }\n")
        write("balloon-compose/build.gradle.kts", "dependencies { api(project(\":balloon\")) }\n")
        write("converters/gson/build.gradle.kts", "dependencies { implementation(projects.balloonCompose) }\n")
        write("app/build.gradle.kts", "dependencies { implementation(project(\":balloon-compose\")) }\n")
    }

    @Test
    fun `keeps selected modules, their project dependencies and parents`() {
        repo()
        assertEquals(listOf(":app", ":benchmark", ":converters:moshi"), ModuleTrimmer.unused(dir, listOf(":converters:gson")))
        assertEquals(listOf(":app", ":benchmark", ":converters", ":converters:gson", ":converters:moshi"), ModuleTrimmer.unused(dir, listOf(":balloon-compose")))
    }

    @Test
    fun `writes a block that configures unused modules empty, and removes it on demand`() {
        repo()
        val settings = File(dir, "settings.gradle.kts")
        ModuleTrimmer.trim(dir, listOf(":balloon"))
        assertTrue(settings.readText().contains("buildFileName = \"${ModuleTrimmer.UNUSED_BUILD_FILE}\""))
        assertTrue(settings.readText().contains("\":app\""))

        ModuleTrimmer.trim(dir, null) // -Pklone.allModules=true
        assertFalse(settings.readText().contains("klone-modules"))
    }

    @Test
    fun `baseline profile producers are not kept`() {
        repo()
        write("settings.gradle.kts", "include(\":balloon\", \":baselineprofile\")\n")
        write("balloon/build.gradle.kts", "dependencies { baselineProfile(project(\":baselineprofile\")) }\n")
        assertEquals(listOf(":baselineprofile"), ModuleTrimmer.unused(dir, listOf(":balloon")))
    }

    @Test
    fun `dynamic project references keep every module`() {
        repo()
        write("balloon/build.gradle.kts", "dependencies { rootProject.subprojects.forEach { implementation(project(it.path)) } }\n")
        assertEquals(emptyList<String>(), ModuleTrimmer.unused(dir, listOf(":balloon")))
    }
}
