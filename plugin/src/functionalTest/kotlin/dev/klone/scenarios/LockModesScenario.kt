package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** CI-style frozen lock and per-dependency updates. */
class LockModesScenario : Scenario() {

    @Test
    fun `frozen fails without lock, passes with it, and never writes it`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "main-1"))
        val host = greeterHost("""gitImplementation("${lib.url}", branch = "main")""")

        val missing = host.buildAndFail("help", "-Pklone.frozen=true").output
        assertTrue(missing.contains("klone.resolved is out of date"), missing)
        assertFalse(host.lockFile.exists())

        assertEquals(listOf("main-1"), host.runApp())
        val lock = host.lockFile.readText()
        lib.commit(Projects.javaLibrary("greeter", "main-2"))
        assertEquals(listOf("main-1"), host.runApp("-Pklone.frozen=true"))
        assertEquals(lock, host.lockFile.readText())
    }

    @Test
    fun `frozen fails when the declared ref changed or a dependency was removed`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0").tag("1.1")
        val host = greeterHost("""gitImplementation("${lib.url}", exact = "1.0")""")
        host.runApp()

        host.write(mapOf("app/build.gradle.kts" to host.projectDir.resolve("app/build.gradle.kts").readText().replace("exact = \"1.0\"", "exact = \"1.1\"")))
        assertTrue(host.buildAndFail("help", "-Pklone.frozen=true").output.contains("is not locked"))

        assertTrue(host.buildAndFail("help", "-Pklone.frozen=true", "-Pklone.update=true").output.contains("cannot be combined"))
    }

    @Test
    fun `update only the named dependency`() {
        val a = remote("alpha", Projects.javaLibrary("alpha", "a1", group = "com.a"))
        val b = remote("beta", Projects.javaLibrary("beta", "b1", group = "com.b"))
        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${a.url}", branch = "main")
                gitImplementation("${b.url}", branch = "main")
            """,
            prints = listOf("com.a.Alpha.version()", "com.b.Beta.version()")
        ))
        assertEquals(listOf("a1", "b1"), host.runApp())
        a.commit(Projects.javaLibrary("alpha", "a2", group = "com.a"))
        b.commit(Projects.javaLibrary("beta", "b2", group = "com.b"))
        assertEquals(listOf("a2", "b1"), host.runApp("-Pklone.update=alpha"))
        assertEquals(listOf("a2", "b2"), host.runApp("-Pklone.update=alpha,beta"))
    }
}

class LockMigrationScenario : Scenario() {

    @Test
    fun `upgrading from 1x keeps the locked commit for from`() {
        val lib = greeterWithTags("1.0.0", "1.5.0")
        val oldCommit = lib.git("rev-parse", "1.0.0")
        val host = greeterHost("""gitImplementation("${lib.url}", from = "1.0.0")""")
        // klone.resolved as written by Klone 1.x (from = exact tag, no resolvedTag)
        host.lockFile.writeText("""
            {
              "version": 1,
              "dependencies": [
                { "url": "${lib.url}", "refType": "tag", "refValue": "1.0.0", "resolvedCommit": "$oldCommit",
                  "resolvedModuleCoordinates": "com.example:greeter", "resolvedAt": "2026-01-01T00:00:00Z" }
              ]
            }
        """.trimIndent())

        assertEquals(listOf("1.0.0"), host.runApp(), "must not jump to 1.5.0 on upgrade")
        val lock = host.lockFile.readText()
        assertTrue(lock.contains("\"refType\": \"range\"") && lock.contains(oldCommit) && lock.contains("\"resolvedTag\": \"1.0.0\""), lock)
        assertEquals(listOf("1.5.0"), host.runApp("-Pklone.update=true"))
    }
}
