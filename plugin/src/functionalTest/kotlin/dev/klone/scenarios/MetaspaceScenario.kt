package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A too small -XX:MaxMetaspaceSize hangs composite builds silently: Klone measures it at the end of the build. */
class MetaspaceScenario : Scenario() {

    private fun hostWithLimit(): dev.klone.fixtures.KloneRunner {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1.0")).tag("1.0")
        return greeterHost("""gitImplementation("${lib.url}", exact = "1.0")""").write(mapOf(
            "gradle.properties" to "org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=1g\n"
        ))
    }

    @Test
    fun `warns with the measured usage, also when the configuration cache is reused`() {
        val host = hostWithLimit()
        // Any real usage is above 1% of 1 GB: forces the warning without exhausting Metaspace
        val args = arrayOf(":app:compileJava", "--configuration-cache", "-Pklone.metaspaceWarnPercent=1")

        val first = host.build(*args).output
        assertTrue(first.contains("Gradle daemon Metaspace at"), first)
        assertTrue(first.contains("-XX:MaxMetaspaceSize="), first)

        host.build(*args) // the first build creates klone.resolved, which invalidates the entry once
        val reused = host.build(*args).output
        assertTrue(reused.contains("Configuration cache entry reused"), reused)
        assertTrue(reused.contains("Gradle daemon Metaspace at"), "the check must survive configuration cache reuse")
    }

    @Test
    fun `stays quiet with the default threshold`() {
        val output = hostWithLimit().build(":app:compileJava").output
        assertFalse(output.contains("Metaspace"), output)
    }
}
