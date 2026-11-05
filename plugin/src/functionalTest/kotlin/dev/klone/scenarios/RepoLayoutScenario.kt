package dev.klone.scenarios

import dev.klone.fixtures.GitRepoFixture
import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Monorepos (`dir`), git submodules, repos without settings, default branches not named main. */
class RepoLayoutScenario : Scenario() {

    @Test
    fun `library in a monorepo subdirectory, via DSL and catalog`() {
        val mono = remote("mono", mapOf("README.md" to "backend + android") +
            Projects.javaLibrary("greeter", "mono-1").mapKeys { "android/sdk/${it.key}" }).tag("1.0.0")

        val host = greeterHost("""gitImplementation("${mono.url}", exact = "1.0.0", dir = "android/sdk")""")
        assertEquals(listOf("mono-1"), host.runApp())

        val viaCatalog = greeterHost("implementation(libs.greeter)", name = "catalog", catalog = """
            [libraries]
            greeter = { module = "com.example:greeter", version = "1.0.0" }
            [metadata]
            klone.greeter = { git = "${mono.url}", dir = "android/sdk" }
        """)
        assertEquals(listOf("mono-1"), viaCatalog.runApp())

        val wrong = greeterHost("""gitImplementation("${mono.url}", exact = "1.0.0", dir = "ios")""", name = "wrong")
        assertTrue(wrong.buildAndFail("help").output.contains("dir \"ios\" does not exist"))
    }

    @Test
    fun `sources in a git submodule are checked out`() {
        val shared = remote("shared-src", Projects.javaClass("com.example", "Greeter", "from-submodule"))
        val lib = GitRepoFixture(File(tempDir, "remotes/with-submodule"))
        lib.commit(mapOf(
            "settings.gradle.kts" to "rootProject.name = \"greeter\"\n",
            "build.gradle.kts" to "plugins { `java-library` }\ngroup = \"com.example\"\nsourceSets.main { java.srcDir(\"vendor/src/main/java\") }\n"
        ))
        lib.git("-c", "protocol.file.allow=always", "submodule", "add", "-q", shared.url, "vendor")
        lib.git("commit", "-q", "-m", "add submodule"); lib.tag("1.0")

        assertEquals(listOf("from-submodule"), greeterHost("""gitImplementation("${lib.url}", exact = "1.0")""").runApp())
    }

    @Test
    fun `single-module repo without settings file and a default branch named trunk`() {
        val lib = GitRepoFixture(File(tempDir, "remotes/greeter"))
        lib.git("checkout", "-q", "-b", "trunk")
        lib.commit(Projects.javaLibrary("greeter", "trunk-1") - "settings.gradle.kts")
        assertEquals(listOf("trunk-1"), greeterHost("""gitImplementation("${lib.url}")""").runApp())
    }
}
