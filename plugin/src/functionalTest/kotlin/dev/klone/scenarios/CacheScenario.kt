package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Klone's cache living alongside Gradle's: cleared with it, refreshed with it, and edits never lost. */
class CacheScenario : Scenario() {

    // Outside @TempDir: on Windows the TestKit daemon keeps its Gradle home open after the test
    private val homes = File(System.getProperty("java.io.tmpdir"), "klone-test-gradle-homes/${java.util.UUID.randomUUID()}")
    private val gradleHome get() = File(homes, "gradle-home")
    private val defaultCache get() = File(gradleHome, "caches/klone")

    private fun hostInGradleHome(dependency: String, name: String = "host") =
        host(name, Projects.host(dependencies = dependency, prints = listOf("Greeter.version()"), imports = listOf("com.example.Greeter")),
            cacheDir = null, testKitDir = gradleHome)

    @Test
    fun `default cache lives in GRADLE_USER_HOME caches and survives deleting Gradle caches`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "main-1"))
        val host = hostInGradleHome("""gitImplementation("${lib.url}", branch = "main")""")
        assertEquals(listOf("main-1"), host.runApp())
        assertTrue(File(defaultCache, "file").isDirectory, "checkout expected under ${defaultCache.absolutePath}")

        // Developer wipes dependency caches (Invalidate Caches / rm -rf ~/.gradle/caches/...): same locked commit comes back
        lib.commit(Projects.javaLibrary("greeter", "main-2"))
        File(defaultCache.parentFile, "modules-2").deleteRecursively()
        defaultCache.deleteRecursively()
        assertEquals(listOf("main-1"), host.runApp())
        assertTrue(defaultCache.isDirectory, "re-cloned into the Gradle cache")

        // Brand-new Gradle home (new machine / CI runner without cache): still the locked commit
        val fresh = host("host", Projects.host(dependencies = """gitImplementation("${lib.url}", branch = "main")""",
            prints = listOf("Greeter.version()"), imports = listOf("com.example.Greeter")), cacheDir = null, testKitDir = File(homes, "fresh-home"))
        assertEquals(listOf("main-1"), fresh.runApp())
    }

    @Test
    fun `refresh-dependencies re-fetches cached tags and reports a moved tag`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "first")).tag("1.0")
        val host = greeterHost("""gitImplementation("${lib.url}", exact = "1.0")""")
        assertEquals(listOf("first"), host.runApp())

        // Someone force-moves the tag upstream
        lib.commit(Projects.javaLibrary("greeter", "moved")); lib.git("tag", "-f", "1.0")
        assertEquals(listOf("first"), host.runApp("-Pklone.update=true"), "cached tag is reused without network")
        val refreshed = host.build(":app:run", "--refresh-dependencies", "-Pklone.update=true")
        assertTrue(refreshed.output.contains("Tag 1.0 of ${lib.url} moved"), refreshed.output)
        assertTrue(refreshed.output.contains("KLONE> moved"))
    }

    @Test
    fun `cache location from klone cacheDir property`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1")).tag("1")
        val custom = File(tempDir, "custom-cache")
        val host = hostInGradleHome("""gitImplementation("${lib.url}", exact = "1")""")
        assertEquals(listOf("1"), host.runApp("-Pklone.cacheDir=${custom.absolutePath}"))
        assertTrue(custom.walkTopDown().any { it.name == "greeter" })
        assertFalse(defaultCache.exists())
    }

    @Test
    fun `local edits inside the cache are stashed, not lost, when the version changes`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        lib.commit(Projects.javaLibrary("greeter", "1.1")); lib.tag("1.1")
        val host = greeterHost("""gitImplementation("${lib.url}", exact = "1.0")""")
        host.runApp()

        val checkout = cacheDir.walkTopDown().first { it.name == "greeter" && File(it, ".git").isDirectory }
        val source = File(checkout, "src/main/java/com/example/Greeter.java")
        source.writeText(source.readText().replace("\"1.0\"", "\"debug-edit\""))
        assertEquals(listOf("debug-edit"), host.runApp(), "edits in the cache are built while on the same version")

        host.write(mapOf("app/build.gradle.kts" to File(host.projectDir, "app/build.gradle.kts").readText().replace("exact = \"1.0\"", "exact = \"1.1\"")))
        val switched = host.build(":app:run")
        assertTrue(switched.output.contains("Saved local edits"), switched.output)
        assertTrue(switched.output.contains("KLONE> 1.1"))
        val stash = ProcessBuilder("git", "stash", "show", "-p").directory(checkout).start().inputStream.bufferedReader().readText()
        assertTrue(stash.contains("debug-edit"), stash)
    }
}
