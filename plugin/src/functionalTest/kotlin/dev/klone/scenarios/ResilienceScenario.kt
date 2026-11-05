package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ResilienceScenario : Scenario() {

    private fun greeterAt(url: String, dependency: String = """gitImplementation("$url", from = "1.0")""", name: String = "host") =
        host(name, Projects.host(dependencies = dependency, prints = listOf("Greeter.version()"), imports = listOf("com.example.Greeter")))

    @Test
    fun `corrupt cache is re-cloned and injected files never block`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        val host = greeterAt(lib.url)
        assertEquals(listOf("1.0"), host.runApp())

        val cached = cacheDir.walkTopDown().first { it.name == "greeter" && File(it, ".git").isDirectory }
        File(cached, ".git/HEAD").delete()
        assertEquals(listOf("1.0"), host.runApp())
    }

    @Test
    fun `offline works from cache and fails clearly without it`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        val host = greeterAt(lib.url)
        assertEquals(listOf("1.0"), host.runApp())
        assertEquals(listOf("1.0"), host.runApp("--offline"))

        val other = remote("other", Projects.javaLibrary("greeter", "x")).tag("1.0")
        val cold = greeterAt(other.url, name = "cold")
        val output = cold.buildAndFail("help", "--offline").output
        assertTrue(output.contains("--offline"), output)
    }

    @Test
    fun `missing repo fails fast with the git error`() {
        val host = greeterAt(dev.klone.model.fileUrl(File(tempDir, "does-not-exist")))
        val output = host.buildAndFail("help").output
        assertTrue(output.contains("[Klone] Failed to resolve"), output)
    }

    @Test
    fun `same repo with conflicting refs is an explicit error`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        val host = greeterAt(lib.url, dependency = """
            gitImplementation("${lib.url}", from = "1.0")
            gitImplementation("${lib.url}.git", branch = "main")
        """)
        val output = host.buildAndFail("help").output
        assertTrue(output.contains("is declared with different refs"), output)
    }

    @Test
    fun `commented-out declarations are ignored`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        val host = greeterAt(lib.url, dependency = """
            // gitImplementation("file:///nowhere/old", from = "0.1")
            gitImplementation("${lib.url}", from = "1.0")
        """)
        assertEquals(listOf("1.0"), host.runApp())
    }

    @Test
    fun `parallel builds sharing one cache`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        val hosts = (1..2).map { greeterAt(lib.url, name = "host$it") }
        val pool = Executors.newFixedThreadPool(2)
        val results = hosts.map { h -> pool.submit<List<String>> { h.runApp() } }.map { it.get(10, TimeUnit.MINUTES) }
        pool.shutdown()
        assertEquals(listOf(listOf("1.0"), listOf("1.0")), results)
    }

    @Test
    fun `configuration cache is reused once the lock file exists`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        val host = greeterAt(lib.url)
        // First build creates klone.resolved, which invalidates the entry once; afterwards it is reused
        host.build(":app:compileJava", "--configuration-cache")
        host.build(":app:compileJava", "--configuration-cache")
        val third = host.build(":app:compileJava", "--configuration-cache").output
        assertTrue(third.contains("Reusing configuration cache"), third)

        // A new commit on a locked ref does not invalidate the cache...
        lib.commit(Projects.javaLibrary("greeter", "2.0"))
        assertTrue(host.build(":app:compileJava", "--configuration-cache").output.contains("Reusing configuration cache"))
    }
}
