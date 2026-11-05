package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitTagScenario : Scenario() {

    @Test
    fun `exact version builds from source and switching version on warm cache works`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0.0")).tag("1.0.0")
        lib.commit(Projects.javaLibrary("greeter", "1.1.0")); lib.tag("v1.1.0")

        fun app(tag: String) = Projects.host(
            dependencies = """gitImplementation("${lib.url}", exact = "$tag")""",
            prints = listOf("Greeter.version()"),
            imports = listOf("com.example.Greeter")
        )

        val host = host(files = app("1.0.0"))
        assertEquals(listOf("1.0.0"), host.runApp())
        assertTrue(host.lockFile.readText().contains("\"refValue\": \"1.0.0\""))

        // Regression: shallow single-branch clone could not see new tags. Also "1.1.0" → tag "v1.1.0".
        host.write(app("1.1.0"))
        assertEquals(listOf("1.1.0"), host.runApp())
    }
}
