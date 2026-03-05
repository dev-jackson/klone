package dev.klone.resolver

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import java.io.File

class GitResolver(private val cacheDir: File) {

    data class ResolvedDependency(
        val localDir: File,
        val commitHash: String
    )

    fun resolve(dep: GitDependency): ResolvedDependency {
        val localDir = File(cacheDir, dep.cacheKey)

        if (localDir.exists()) {
            fetch(localDir, dep)
        } else {
            clone(dep, localDir)
        }

        checkout(localDir, dep.ref)
        val commit = resolveCommit(localDir)
        return ResolvedDependency(localDir, commit)
    }

    private fun clone(dep: GitDependency, targetDir: File) {
        targetDir.parentFile.mkdirs()
        val args = when (val ref = dep.ref) {
            is GitRef.Tag -> listOf("git", "clone", "--depth", "1", "--branch", ref.name, dep.url, targetDir.absolutePath)
            is GitRef.Branch -> listOf("git", "clone", "--depth", "1", "--branch", ref.name, dep.url, targetDir.absolutePath)
            is GitRef.Commit -> listOf("git", "clone", dep.url, targetDir.absolutePath)
        }
        exec(args)
    }

    private fun fetch(localDir: File, dep: GitDependency) {
        when (dep.ref) {
            is GitRef.Tag, is GitRef.Branch ->
                exec(listOf("git", "fetch", "--depth", "1", "origin"), workDir = localDir)
            is GitRef.Commit ->
                exec(listOf("git", "fetch", "origin"), workDir = localDir)
        }
    }

    private fun checkout(localDir: File, ref: GitRef) {
        val refName = when (ref) {
            is GitRef.Tag -> "tags/${ref.name}"
            is GitRef.Branch -> "origin/${ref.name}"
            is GitRef.Commit -> ref.hash
        }
        exec(listOf("git", "checkout", "--detach", refName), workDir = localDir)
    }

    private fun resolveCommit(localDir: File): String =
        exec(listOf("git", "rev-parse", "HEAD"), workDir = localDir).trim()

    private fun exec(args: List<String>, workDir: File? = null): String {
        val process = ProcessBuilder(args)
            .apply {
                if (workDir != null) directory(workDir)
                redirectErrorStream(true)
            }
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw KloneGitException("Git command failed (exit $exitCode): ${args.joinToString(" ")}\n$output")
        }
        return output
    }
}

class KloneGitException(message: String) : RuntimeException(message)
