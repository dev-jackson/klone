package dev.klone

import dev.klone.composite.HostPropagation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidPluginAlignmentTest {

    @TempDir
    lateinit var dir: File

    private fun write(path: String, content: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(content) }

    @Test
    fun `pins the clone and its included builds to the host AGP`() {
        val root = write("settings.gradle.kts", "pluginManagement {\n    includeBuild(\"build-logic\")\n}\nrootProject.name = \"lib\"\n")
        val logic = write("build-logic/settings.gradle", "rootProject.name = 'build-logic'\n")

        assertTrue(HostPropagation.alignAndroidPlugin(dir, "9.0.1", "9.0.0"))

        assertTrue(root.readText().contains("useVersion(\"9.0.1\")"))
        assertTrue(root.readText().startsWith("pluginManagement {"), "the clone's own content stays first")
        assertTrue(logic.readText().contains("useVersion('9.0.1')"), "Groovy settings get the Groovy block")
    }

    @Test
    fun `AGP 8 clone lifted to AGP 9 keeps AGP 8 behavior, without overriding its own keys`() {
        write("settings.gradle.kts", "rootProject.name = \"lib\"\n")
        val props = write("gradle.properties", "android.newDsl=true\n")

        HostPropagation.alignAndroidPlugin(dir, "9.0.1", "8.13.2")
        assertTrue(props.readText().contains("android.builtInKotlin=false"))
        assertFalse(props.readText().contains("android.newDsl=false"), "the clone's own value wins")

        // Same major: no compat flags
        HostPropagation.alignAndroidPlugin(dir, "9.0.1", "9.0.0")
        assertFalse(props.readText().contains("klone-agp-compat"))
    }

    @Test
    fun `same version or unknown versions leave the files untouched`() {
        val original = "rootProject.name = \"lib\"\n"
        val root = write("settings.gradle.kts", original)

        assertFalse(HostPropagation.alignAndroidPlugin(dir, "9.0.1", "9.0.1"))
        assertFalse(HostPropagation.alignAndroidPlugin(dir, null, "9.0.0"))
        assertEquals(original, root.readText())
    }

    @Test
    fun `block is replaced when the host version changes and removed once versions match`() {
        val root = write("settings.gradle.kts", "rootProject.name = \"lib\"\n")
        HostPropagation.alignAndroidPlugin(dir, "9.0.1", "9.0.0")
        HostPropagation.alignAndroidPlugin(dir, "9.0.2", "9.0.0")
        assertEquals(1, Regex("<klone-align>").findAll(root.readText()).count())
        assertTrue(root.readText().contains("9.0.2"))

        HostPropagation.alignAndroidPlugin(dir, "9.0.0", "9.0.0")
        assertFalse(root.readText().contains("klone-align"))
    }
}

class RootProjectNameTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `names the root when it would clash with a subproject named like the repo`() {
        val repo = File(tempDir, "Balloon").apply { mkdirs() }
        val settings = File(repo, "settings.gradle.kts").apply { writeText("include(\":app\")\ninclude(\":balloon\")\n") }
        HostPropagation.fixRootProjectName(repo)
        assertTrue(settings.readText().contains("rootProject.name = \"Balloon-root\""))
        // Idempotent
        HostPropagation.fixRootProjectName(repo)
        assertEquals(1, Regex("rootProject.name").findAll(settings.readText()).count())
    }

    @Test
    fun `renames a declared root name that clashes`() {
        val repo = File(tempDir, "effects").apply { mkdirs() }
        val settings = File(repo, "settings.gradle.kts").apply { writeText("rootProject.name = \"compose-effects\"\ninclude(\":compose-effects\")\n") }
        HostPropagation.fixRootProjectName(repo)
        assertTrue(settings.readText().trimEnd().endsWith("rootProject.name = \"compose-effects-root\"\n// </klone-root-name>"))
    }

    @Test
    fun `leaves builds that name their root or have no clash`() {
        val named = File(tempDir, "sdk").apply { mkdirs() }
        val a = File(named, "settings.gradle.kts").apply { writeText("rootProject.name = \"x\"\ninclude(\":sdk\")\n") }
        val plain = File(tempDir, "kit").apply { mkdirs() }
        val b = File(plain, "settings.gradle").apply { writeText("include ':core'\n") }
        HostPropagation.fixRootProjectName(named)
        HostPropagation.fixRootProjectName(plain)
        assertFalse(a.readText().contains("klone-root-name"))
        assertFalse(b.readText().contains("klone-root-name"))
    }
}
