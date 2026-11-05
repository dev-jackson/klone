package dev.klone.fixtures

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * Runs real Gradle builds of an emulated host project with the plugin under test,
 * an isolated Klone cache. Fixture builds need no network.
 */
class KloneRunner(
    val projectDir: File,
    // null = Klone's default location ($GRADLE_USER_HOME/caches/klone)
    val cacheDir: File?,
    // Gradle user home for the build; isolate it to test Gradle-cache interactions
    val testKitDir: File? = null
) {

    fun write(files: Map<String, String>) = apply {
        files.forEach { (path, content) -> File(projectDir, path).apply { parentFile.mkdirs(); writeText(content) } }
    }

    fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .apply { testKitDir?.let { withTestKitDir(it) } }
            .apply { System.getProperty("klone.test.gradleVersion")?.let { withGradleVersion(it) } }
            .withEnvironment((System.getenv() - "KLONE_CACHE_DIR") + listOfNotNull(cacheDir?.let { "KLONE_CACHE_DIR" to it.absolutePath }))
            .withArguments(listOf("--stacktrace") + args)
            .forwardOutput()

    fun build(vararg args: String): BuildResult = runner(*args).build()

    fun buildAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    /** Runs `:app:run` and returns the lines printed by the host's Main (prefixed `KLONE> `). */
    fun runApp(vararg args: String): List<String> =
        build(":app:run", "-q", *args).output.lines()
            .filter { it.startsWith("KLONE> ") }
            .map { it.removePrefix("KLONE> ") }

    val lockFile: File get() = File(projectDir, "klone.resolved")
}
