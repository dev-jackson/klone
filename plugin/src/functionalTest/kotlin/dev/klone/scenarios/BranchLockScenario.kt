package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BranchLockScenario : Scenario() {

    @Test
    fun `branch is pinned by klone resolved until -Pklone update`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "main-1"))
        val host = host(files = Projects.host(
            dependencies = """gitImplementation("${lib.url}", branch = "main")""",
            prints = listOf("Greeter.version()"),
            imports = listOf("com.example.Greeter")
        ))

        assertEquals(listOf("main-1"), host.runApp())
        val firstLock = host.lockFile.readText()
        assertTrue(firstLock.contains(lib.headSha()))

        lib.commit(Projects.javaLibrary("greeter", "main-2"))
        assertEquals(listOf("main-1"), host.runApp(), "locked commit must win over the moving branch")
        assertEquals(firstLock, host.lockFile.readText(), "no-op build must not rewrite the lock (resolvedAt stable)")

        assertEquals(listOf("main-2"), host.runApp("-Pklone.update=true"))
        assertTrue(host.lockFile.readText().contains(lib.headSha()))
        assertEquals(listOf("main-2"), host.runApp())
    }

    @Test
    fun `changing the declared ref ignores the old lock entry`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "main"))
        lib.branch("dev").commit(Projects.javaLibrary("greeter", "dev"))
        lib.checkout("main")

        fun app(branch: String) = Projects.host(
            dependencies = """gitImplementation("${lib.url}", branch = "$branch")""",
            prints = listOf("Greeter.version()"),
            imports = listOf("com.example.Greeter")
        )
        val host = host(files = app("main"))
        assertEquals(listOf("main"), host.runApp())
        host.write(app("dev"))
        assertEquals(listOf("dev"), host.runApp())
    }
}
