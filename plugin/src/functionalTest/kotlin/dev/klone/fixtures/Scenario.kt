package dev.klone.fixtures

import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Base for end-to-end scenarios: isolated remotes, cache and host project per test. */
abstract class Scenario {

    @TempDir
    lateinit var tempDir: File

    val cacheDir: File get() = File(tempDir, "klone-cache")

    /** A git remote at `remotes/<name>`, optionally seeded with a first commit. */
    fun remote(name: String, files: Map<String, String>? = null): GitRepoFixture =
        GitRepoFixture(File(tempDir, "remotes/$name")).also { repo -> files?.let { repo.commit(it, "initial") } }

    fun host(name: String = "host", files: Map<String, String>, cacheDir: File? = this.cacheDir, testKitDir: File? = null): KloneRunner =
        KloneRunner(File(tempDir, name), cacheDir, testKitDir).write(files)

    /** Writes a project's files under `tempDir/<path>` (local dependencies). */
    fun files(path: String, files: Map<String, String>): File =
        File(tempDir, path).also { dir -> files.forEach { (p, c) -> File(dir, p).apply { parentFile.mkdirs(); writeText(c) } } }

    /** Greeter library remote with one tag per version (each `Greeter.version()` returns its version). */
    fun greeterWithTags(vararg tags: String, pattern: String = "{version}"): GitRepoFixture {
        val repo = remote("greeter")
        tags.forEach { v -> repo.commit(Projects.javaLibrary("greeter", v), v); repo.tag(pattern.replace("{version}", v)) }
        return repo
    }

    fun greeterHost(dependency: String, name: String = "host", catalog: String? = null): KloneRunner =
        host(name, Projects.host(dependencies = dependency, prints = listOf("Greeter.version()"), imports = listOf("com.example.Greeter"), catalog = catalog))
}
