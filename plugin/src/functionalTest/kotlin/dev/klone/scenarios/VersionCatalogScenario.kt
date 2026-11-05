package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class VersionCatalogScenario : Scenario() {

    @Test
    fun `libs versions toml metadata maps standard catalog libraries to git and local sources`() {
        val greeter = remote("greeter", Projects.javaLibrary("greeter", "1.0.0")).tag("v1.0.0")
        val kit = remote("kit", Projects.multiModuleLibrary("kit", "1", listOf("core", "ui"))).tag("1")
        val design = remote("design", Projects.javaLibrary("design", "design-main", group = "com.org"))
        File(tempDir, "utils").also { dir ->
            Projects.javaLibrary("utils", "utils-local", group = "com.org").forEach { (p, c) -> File(dir, p).apply { parentFile.mkdirs(); writeText(c) } }
        }

        val host = host(files = Projects.host(
            dependencies = """
                implementation(libs.greeter)
                implementation(libs.kit.ui)
                implementation(libs.design)
                implementation(libs.utils)
            """,
            prints = listOf("Greeter.version()", "Ui.version()", "com.org.Design.version()", "com.org.Utils.version()"),
            imports = listOf("com.example.Greeter", "com.example.Ui"),
            catalog = """
                [versions]
                greeter = "1.0.0"

                [libraries]
                greeter = { module = "com.example:greeter", version.ref = "greeter" }
                kit-ui = { module = "com.example:ui", version = "1" }
                design = { module = "com.org:design" }
                utils = { module = "com.org:utils" }

                [metadata]
                format.version = "1.1"
                klone.greeter = "${greeter.url}"
                klone.kit-ui = { git = "${kit.url}", module = "ui" }
                klone.design = { git = "${design.url}", branch = "main" }
                klone.utils = { path = "../utils" }
            """
        ))
        assertEquals(listOf("1.0.0", "ui-1", "design-main", "utils-local"), host.runApp())
    }

    @Test
    fun `gitImplementation reads libs versions references across lines`() {
        val greeter = remote("greeter", Projects.javaLibrary("greeter", "2.5.0")).tag("2.5.0")
        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation(
                    "${greeter.url}",
                    from = libs.versions.greeter.get(),
                )
            """,
            prints = listOf("Greeter.version()"),
            imports = listOf("com.example.Greeter"),
            catalog = """
                [versions]
                greeter = "2.5.0"
            """
        ))
        assertEquals(listOf("2.5.0"), host.runApp())
    }

    @Test
    fun `invalid klone metadata fails with a clear message`() {
        val host = host(files = Projects.host(
            dependencies = "",
            prints = emptyList(),
            catalog = """
                [libraries]
                lib = { module = "g:lib" }
                [metadata]
                klone.lib = { git = "https://example.com/lib", tag = "1", branch = "main" }
            """
        ))
        val output = host.buildAndFail("help").output
        assertTrue(output.contains("metadata.klone.lib: Use only one of"), output)
    }
}
