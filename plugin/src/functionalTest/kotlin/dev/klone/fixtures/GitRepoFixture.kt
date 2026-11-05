package dev.klone.fixtures

import java.io.File

/** A real git repository on disk acting as a remote (`file://` URL), like GitHub would. */
class GitRepoFixture(val dir: File) {

    val url: String get() = dev.klone.model.fileUrl(dir)

    init {
        dir.mkdirs()
        git("init", "-q", "-b", "main")
    }

    /** Writes [files] (path → content), commits them and returns the new commit hash. */
    fun commit(files: Map<String, String>, message: String = "update"): String {
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs(); writeText(content) } }
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", message)
        return headSha()
    }

    fun tag(name: String) = apply { git("tag", name) }

    fun branch(name: String) = apply { git("checkout", "-q", "-B", name) }

    fun checkout(name: String) = apply { git("checkout", "-q", name) }

    fun headSha(): String = git("rev-parse", "HEAD")

    fun git(vararg args: String): String {
        val command = listOf(
            "git", "-c", "user.name=Klone Test", "-c", "user.email=test@klone.dev",
            "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", "-c", "core.hooksPath=/dev/null"
        ) + args
        val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }
}
