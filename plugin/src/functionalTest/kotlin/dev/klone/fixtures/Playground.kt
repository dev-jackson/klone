package dev.klone.fixtures

import java.io.File
import java.util.Properties

/**
 * Generates a hands-on playground: local git "remotes", a local library and a host app wired
 * with every Klone feature. Run via `./gradlew :plugin:generatePlayground`.
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: "build/playground").absoluteFile
    root.deleteRecursively()

    val greeter = GitRepoFixture(File(root, "remotes/greeter"))
    greeter.commit(Projects.javaLibrary("greeter", "1.0.0"), "1.0.0"); greeter.tag("1.0.0")
    greeter.commit(Projects.javaLibrary("greeter", "1.1.0"), "1.1.0"); greeter.tag("v1.1.0")

    val kit = GitRepoFixture(File(root, "remotes/kit"))
    kit.commit(Projects.multiModuleLibrary("kit", "main", listOf("core", "ui")))

    Projects.javaLibrary("shared", "local-source", group = "com.org").forEach { (path, content) ->
        File(root, "shared/$path").apply { parentFile.mkdirs(); writeText(content) }
    }

    // Plugin jars from the build (same classpath TestKit injects)
    val metadata = Properties().apply {
        Thread.currentThread().contextClassLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use { load(it) }
    }
    val classpath = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
        .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    val host = Projects.host(
        dependencies = """
                // 1) Version catalog: standard libs.* accessors, sources in [metadata]
                implementation(libs.greeter)
                implementation(libs.shared)
                // 2) Direct git declaration
                gitImplementation("${kit.url}", module = "ui", branch = "main")
        """,
        prints = listOf("Greeter.version()", "com.org.Shared.version()", "Ui.version()"),
        imports = listOf("com.example.Greeter", "com.example.Ui"),
        catalog = """
            [versions]
            greeter = "1.1.0"

            [libraries]
            greeter = { module = "com.example:greeter", version.ref = "greeter" }
            shared = { module = "com.org:shared" }

            [metadata]
            klone.greeter = "${greeter.url}"
            klone.shared = { path = "../shared" }
        """
    ).toMutableMap()
    host["settings.gradle.kts"] = host.getValue("settings.gradle.kts").replace(
        """plugins { id("io.github.dev-jackson.klone") }""",
        "buildscript { dependencies { classpath(files($classpath)) } }\napply(plugin = \"io.github.dev-jackson.klone\")"
    )
    host.forEach { (path, content) -> File(root, "host/$path").apply { parentFile.mkdirs(); writeText(content) } }

    println("""
        Playground ready: $root
          cd ${File(root, "host")}
          <klone>/gradlew :app:run                 # prints versions from git + local sources
        Try:
          - edit gradle/libs.versions.toml (greeter = "1.0.0") and run again
          - commit to ${kit.dir} and run with -Pklone.update=true
          - inspect klone.resolved
    """.trimIndent())
}
