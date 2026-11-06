package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Cloned repos that use Klone themselves: their settings run as included builds after the host's. */
class NestedKloneScenario : Scenario() {

    private fun featureUsingKlone(baseUrl: String) = remote("feature", mapOf(
        "settings.gradle.kts" to "plugins { id(\"io.github.dev-jackson.klone\") }\nrootProject.name = \"feature\"\n",
        "build.gradle.kts" to """
            plugins { `java-library`; id("io.github.dev-jackson.klone.project") }
            group = "com.internal"
            dependencies { gitImplementation("$baseUrl", from = "2.0", configuration = "api") }
        """.trimIndent() + "\n",
        "src/main/java/com/internal/Feature.java" to
            "package com.internal;\npublic class Feature { public static String value() { return \"feature+\" + Base.version(); } }\n"
    )).tag("1.0")

    @Test
    fun `repo declaring its own git dependencies with Klone`() {
        val base = remote("base", Projects.javaLibrary("base", "base-2.0", group = "com.internal")).tag("2.0")
        val feature = featureUsingKlone(base.url)

        val host = host(files = Projects.host(
            dependencies = """gitImplementation("${feature.url}", from = "1.0")""",
            prints = listOf("Feature.value()"),
            imports = listOf("com.internal.Feature")
        ))
        assertEquals(listOf("feature+base-2.0"), host.runApp())
        // Second build: every repo is on its locked commit — the fast path must keep working
        assertEquals(listOf("feature+base-2.0"), host.runApp())
    }

    @Test
    fun `host and nested repo declaring the same dependency share one checkout`() {
        val base = remote("base", Projects.javaLibrary("base", "base-2.0", group = "com.internal")).tag("2.0")
        val feature = featureUsingKlone(base.url)

        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${feature.url}", from = "1.0")
                gitImplementation("${base.url}", from = "2.0")
            """,
            prints = listOf("Feature.value()", "Base.version()"),
            imports = listOf("com.internal.Feature", "com.internal.Base")
        ))
        assertEquals(listOf("feature+base-2.0", "base-2.0"), host.runApp())
    }

    @Test
    fun `works with isolated projects`() {
        val base = remote("base", Projects.javaLibrary("base", "base-2.0", group = "com.internal")).tag("2.0")
        val feature = featureUsingKlone(base.url)
        val host = host(files = Projects.host(
            dependencies = """gitImplementation("${feature.url}", from = "1.0")""",
            prints = listOf("Feature.value()"),
            imports = listOf("com.internal.Feature")
        ))
        // Property name changed in Gradle 9; the unused one is ignored
        val isolated = arrayOf("-Dorg.gradle.unsafe.isolated-projects=true", "-Dorg.gradle.isolated-projects=true")
        assertEquals(listOf("feature+base-2.0"), host.runApp(*isolated))
        assertEquals(listOf("feature+base-2.0"), host.runApp(*isolated))
    }
}
