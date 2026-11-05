package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class TransitiveAndLocalScenario : Scenario() {

    @Test
    fun `internal git library depending on another internal git library by maven coordinates`() {
        val base = remote("base", Projects.javaLibrary("base", "base-2.0", group = "com.internal")).tag("2.0")
        // feature declares base by coordinates, as it would against a Maven repo
        val feature = remote("feature", Projects.javaLibrary(
            "feature", "feature-1.0", group = "com.internal",
            dependencies = """    api("com.internal:base:2.0")"""
        ) + mapOf(
            "src/main/java/com/internal/Combined.java" to
                "package com.internal;\npublic class Combined { public static String value() { return Feature.version() + \"+\" + Base.version(); } }\n"
        )).tag("1.0")

        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${feature.url}", from = "1.0")
                gitImplementation("${base.url}", from = "2.0")
            """,
            prints = listOf("Combined.value()"),
            imports = listOf("com.internal.Combined")
        ))
        assertEquals(listOf("feature-1.0+base-2.0"), host.runApp())
    }

    @Test
    fun `local path dependency relative to the host root, plus settings DSL`() {
        File(tempDir, "shared").also { dir ->
            Projects.javaLibrary("shared", "local-dev").forEach { (p, c) -> File(dir, p).apply { parentFile.mkdirs(); writeText(c) } }
        }
        File(tempDir, "tools").also { dir ->
            Projects.javaLibrary("tools", "tools-dev", group = "com.tools").forEach { (p, c) -> File(dir, p).apply { parentFile.mkdirs(); writeText(c) } }
        }
        val host = host(files = Projects.host(
            dependencies = """
                localImplementation("../shared")
                implementation("com.tools:tools")
            """,
            settingsExtra = """klone { local("../tools") { module("com.tools:tools") } }""",
            prints = listOf("Shared.version()", "com.tools.Tools.version()"),
            imports = listOf("com.example.Shared")
        ))
        assertEquals(listOf("local-dev", "tools-dev"), host.runApp())
    }

    @Test
    fun `two repos with the same directory name do not collide`() {
        val a = remote("org-a/utils", Projects.javaLibrary("utils", "a", group = "com.a")).tag("1")
        val b = remote("org-b/utils", Projects.javaLibrary("utils", "b", group = "com.b")).tag("1")
        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${a.url}", from = "1")
                gitImplementation("${b.url}", from = "1")
            """,
            prints = listOf("com.a.Utils.version()", "com.b.Utils.version()")
        ))
        assertEquals(listOf("a", "b"), host.runApp())
    }
}
