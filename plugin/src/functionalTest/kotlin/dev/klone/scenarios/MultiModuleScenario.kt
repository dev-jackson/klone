package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiModuleScenario : Scenario() {

    private fun kit() = remote("kit", Projects.multiModuleLibrary("kit", "1.0", listOf("core", "network", "ui"))).tag("1.0")

    @Test
    fun `module, modules and all-modules selection`() {
        val kit = kit()
        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${kit.url}", module = "core", from = "1.0")
                gitImplementation(
                    "${kit.url}",
                    modules = listOf("network", "ui"),
                    from = "1.0",
                )
            """,
            prints = listOf("Core.version()", "Network.version()", "Ui.version()"),
            imports = listOf("com.example.Core", "com.example.Network", "com.example.Ui")
        ))
        assertEquals(listOf("core-1.0", "network-1.0", "ui-1.0"), host.runApp())
    }

    @Test
    fun `all modules when none requested, sample app ignored`() {
        val kit = kit()
        val host = host(files = Projects.host(
            dependencies = """gitImplementation("${kit.url}", from = "1.0")""",
            prints = listOf("Core.version()", "Ui.version()"),
            imports = listOf("com.example.Core", "com.example.Ui")
        ))
        assertEquals(listOf("core-1.0", "ui-1.0"), host.runApp())
        assertTrue(!host.lockFile.readText().contains("sample"))
    }

    @Test
    fun `unknown module fails with the list of available modules`() {
        val kit = kit()
        val host = host(files = Projects.host(
            dependencies = """gitImplementation("${kit.url}", module = "nope", from = "1.0")""",
            prints = emptyList()
        ))
        val output = host.buildAndFail(":app:compileJava").output
        assertTrue(output.contains("Module 'nope' not found in kit. Available: [core, network, ui]"), output)
    }
}
