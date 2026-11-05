package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test

/** SPM-style requirements: from (next major), exact, upToNextMinor, ranges, tag patterns. */
class SpmVersionScenario : Scenario() {

    @ParameterizedTest(name = "{0} picks {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "from = \"1.0.0\"                 | 1.3.1",
            "from = \"2.0.0\"                 | 2.1.0",
            "exact = \"1.2.0\"                | 1.2.0",
            "upToNextMinor = \"1.2.0\"        | 1.2.5",
            "version = \"^1.2\"               | 1.3.1",
            "version = \"~1.2.0\"             | 1.2.5",
            "version = \">=1.2.0 <1.3.0\"     | 1.2.5",
            "version = \"1.+\"                | 1.3.1",
            "version = \"[1.0,2.0)\"          | 1.3.1",
            "version = \"latest.release\"     | 2.1.0",
            "tag = \"1.2.0\"                  | 1.2.0",
        ]
    )
    fun `version requirement resolves the right tag`(requirement: String, expected: String) {
        val lib = greeterWithTags("1.0.0", "1.2.0", "1.2.5", "1.3.1", "2.0.0", "2.1.0", "3.0.0-beta1")
        val host = greeterHost("""gitImplementation("${lib.url}", $requirement)""")
        assertEquals(listOf(expected), host.runApp())
        assertTrue(host.lockFile.readText().contains("\"resolvedTag\": \"$expected\""))
    }

    @Test
    fun `range is pinned by the lock until update, then moves within the range only`() {
        val lib = greeterWithTags("1.0.0", "1.1.0")
        val host = greeterHost("""gitImplementation("${lib.url}", from = "1.0.0")""")
        assertEquals(listOf("1.1.0"), host.runApp())

        lib.commit(Projects.javaLibrary("greeter", "1.5.0")); lib.tag("1.5.0")
        lib.commit(Projects.javaLibrary("greeter", "2.0.0")); lib.tag("2.0.0")
        assertEquals(listOf("1.1.0"), host.runApp(), "lock pins the resolved tag")
        assertEquals(listOf("1.5.0"), host.runApp("-Pklone.update=true"), "update stays below the next major")
    }

    @Test
    fun `tag pattern and v-prefixed tags`() {
        val lib = greeterWithTags("1.0.0", "1.4.0", pattern = "greeter-{version}")
        val host = greeterHost("""gitImplementation("${lib.url}", from = "1.0.0", tagPattern = "greeter-{version}")""")
        assertEquals(listOf("1.4.0"), host.runApp())

        val vLib = remote("vlib", Projects.javaLibrary("greeter", "9.1.0")).tag("v9.1.0")
        val vHost = greeterHost("""gitImplementation("${vLib.url}", exact = "9.1.0")""", name = "vhost")
        assertEquals(listOf("9.1.0"), vHost.runApp())
    }

    @Test
    fun `catalog SPM keys and Gradle ranges in the library version`() {
        val lib = greeterWithTags("1.0.0", "1.2.0", "2.0.0")
        val host = greeterHost("implementation(libs.greeter)", catalog = """
            [libraries]
            greeter = { module = "com.example:greeter", version = "1.+" }
            [metadata]
            klone.greeter = "${lib.url}"
        """)
        assertEquals(listOf("1.2.0"), host.runApp())

        host.write(Projects.host(
            dependencies = "implementation(libs.greeter)", prints = listOf("Greeter.version()"), imports = listOf("com.example.Greeter"),
            catalog = """
                [libraries]
                greeter = { module = "com.example:greeter" }
                [metadata]
                klone.greeter = { git = "${lib.url}", from = "2.0.0" }
            """
        ))
        assertEquals(listOf("2.0.0"), host.runApp())
    }

    @Test
    fun `clear errors for unsatisfiable or conflicting requirements`() {
        val lib = greeterWithTags("1.0.0")
        val none = greeterHost("""gitImplementation("${lib.url}", from = "5.0.0")""")
        assertTrue(none.buildAndFail("help").output.contains("No tag of ${lib.url} matches version: >=5.0.0 <6.0.0"))

        val both = greeterHost("""gitImplementation("${lib.url}", from = "1.0.0", branch = "main")""", name = "both")
        assertTrue(both.buildAndFail("help").output.contains("Use only one of from, exact, upToNextMinor, version, tag, branch, commit"))
    }
}
