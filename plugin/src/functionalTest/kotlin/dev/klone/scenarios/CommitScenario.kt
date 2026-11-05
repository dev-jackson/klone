package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommitScenario : Scenario() {

    @Test
    fun `full and short commit hashes, including commits missing from a shallow cache`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "c1"))
        val c1 = lib.headSha()
        lib.commit(Projects.javaLibrary("greeter", "c2"))

        fun app(commit: String) = Projects.host(
            dependencies = """gitImplementation("${lib.url}", commit = "$commit")""",
            prints = listOf("Greeter.version()"),
            imports = listOf("com.example.Greeter")
        )
        val host = host(files = app(lib.headSha()))
        assertEquals(listOf("c2"), host.runApp())

        // c1 is not in the shallow cache: must be fetched
        host.write(app(c1.take(8)))
        assertEquals(listOf("c1"), host.runApp())
    }
}
