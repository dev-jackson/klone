package dev.klone.scenarios

import dev.klone.fixtures.GitRepoFixture
import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One version per repo across the whole graph, like SPM: the host resolves what nested Klone repos
 * declare too, picks the highest tag every requirement accepts, and fails when none does.
 */
class VersionSolvingScenario : Scenario() {

    private fun sdk(vararg versions: String): GitRepoFixture {
        val repo = remote("sdk")
        versions.forEach { v -> repo.commit(Projects.javaLibrary("sdk", "sdk-$v", group = "com.internal"), v); repo.tag(v) }
        return repo
    }

    /** A repo that uses Klone itself to depend on [sdkRef] of the sdk. */
    private fun feature(sdkUrl: String, sdkRef: String) = remote("feature", mapOf(
        "settings.gradle.kts" to "plugins { id(\"io.github.dev-jackson.klone\") }\nrootProject.name = \"feature\"\n",
        "build.gradle.kts" to """
            plugins { `java-library`; id("io.github.dev-jackson.klone.project") }
            group = "com.internal"
            dependencies { gitImplementation("$sdkUrl", $sdkRef, configuration = "api") }
        """.trimIndent() + "\n",
        "src/main/java/com/internal/Feature.java" to
            "package com.internal;\npublic class Feature { public static String sdk() { return Sdk.version(); } }\n"
    )).tag("1.0")

    @Test
    fun `host and nested requirements resolve to one version every requirement accepts`() {
        val sdk = sdk("1.0.0", "1.2.0", "1.3.0")
        val feature = feature(sdk.url, "from = \"1.0.0\"")
        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${feature.url}", from = "1.0")
                gitImplementation("${sdk.url}", exact = "1.2.0")
            """,
            prints = listOf("Feature.sdk()", "Sdk.version()"),
            imports = listOf("com.internal.Feature", "com.internal.Sdk")
        ))

        // 1.3.0 satisfies the nested range but not the host's exact 1.2.0: both must see 1.2.0
        assertEquals(listOf("sdk-1.2.0", "sdk-1.2.0"), host.runApp())
        assertEquals(listOf("sdk-1.2.0", "sdk-1.2.0"), host.runApp())
        assertFalse(cacheFile("feature", "klone.resolved").exists(), "nested repos never write their own lock")
    }

    @Test
    fun `incompatible requirements fail naming who requires what`() {
        val sdk = sdk("1.0.0", "1.2.0")
        val feature = feature(sdk.url, "from = \"1.2.0\"")
        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${feature.url}", from = "1.0")
                gitImplementation("${sdk.url}", exact = "1.0.0")
            """,
            prints = listOf("Sdk.version()"),
            imports = listOf("com.internal.Sdk")
        ))

        val output = host.buildAndFail(":app:compileJava").output
        assertTrue(output.contains("No version of"), output)
        assertTrue(output.contains("host") && output.contains("1.0.0"), output)
        assertTrue(output.contains("feature") && output.contains(">=1.2.0"), output)
    }

    @Test
    fun `transitive-only repos are resolved and locked by the host`() {
        val sdk = sdk("1.0.0", "1.3.0")
        val feature = feature(sdk.url, "from = \"1.0.0\"")
        val host = host(files = Projects.host(
            dependencies = """gitImplementation("${feature.url}", from = "1.0")""",
            prints = listOf("Feature.sdk()"),
            imports = listOf("com.internal.Feature")
        ))

        assertEquals(listOf("sdk-1.3.0"), host.runApp())
        assertTrue(host.lockFile.readText().contains(sdk.url), "the host lock pins transitive repos too")

        // A new release is not picked up until asked for: the host lock pins it
        sdk.commit(Projects.javaLibrary("sdk", "sdk-1.4.0", group = "com.internal"), "1.4.0"); sdk.tag("1.4.0")
        assertEquals(listOf("sdk-1.3.0"), host.runApp())
        assertEquals(listOf("sdk-1.4.0"), host.runApp("-Pklone.update=true"))
    }

    /** A file inside the cached checkout of [repo]. */
    private fun cacheFile(repo: String, path: String): File =
        cacheDir.walkTopDown().first { it.isDirectory && it.name.startsWith(repo) && File(it, ".git").exists() }.resolve(path)
}
