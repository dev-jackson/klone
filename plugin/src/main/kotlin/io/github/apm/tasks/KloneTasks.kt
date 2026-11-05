package dev.klone.tasks

import dev.klone.model.GitRef
import dev.klone.model.SemVer
import dev.klone.model.deleteTree
import dev.klone.resolver.GitResolver
import org.gradle.api.DefaultTask
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.util.concurrent.TimeUnit

/** Maintenance tasks on the root project: kloneList, kloneOutdated, kloneClean. */
object KloneTasks {

    data class ResolvedRepo(
        val url: String,
        val refType: String,
        val refValue: String,
        val tagPattern: String?,
        val commit: String,
        val tag: String?,
        val localDir: String
    ) {
        fun encode() = listOf(url, refType, refValue, tagPattern.orEmpty(), commit, tag.orEmpty(), localDir).joinToString("\t")

        companion object {
            fun decode(line: String) = line.split('\t').let {
                ResolvedRepo(it[0], it[1], it[2], it[3].ifEmpty { null }, it[4], it[5].ifEmpty { null }, it[6])
            }
        }
    }

    fun register(settings: Settings, repos: List<ResolvedRepo>, cacheDir: File) {
        val encoded = repos.map { it.encode() }
        settings.gradle.rootProject { project ->
            project.tasks.register("kloneList", KloneListTask::class.java) { t ->
                t.group = "klone"; t.description = "Lists git dependencies and the commit each one resolved to."
                t.repos.set(encoded)
            }
            project.tasks.register("kloneOutdated", KloneOutdatedTask::class.java) { t ->
                t.group = "klone"; t.description = "Shows newer tags/commits available for git dependencies."
                t.repos.set(encoded); t.cacheDir.set(cacheDir.absolutePath)
            }
            project.tasks.register("kloneClean", KloneCleanTask::class.java) { t ->
                t.group = "klone"; t.description = "Deletes cached checkouts of this build's git dependencies (--all, --unused-days=N)."
                t.repos.set(encoded); t.cacheDir.set(cacheDir.absolutePath)
            }
        }
    }
}

abstract class KloneListTask : DefaultTask() {
    @get:Input abstract val repos: ListProperty<String>

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun list() {
        val all = repos.get().map { KloneTasks.ResolvedRepo.decode(it) }
        if (all.isEmpty()) { logger.quiet("No git dependencies."); return }
        all.forEach { r ->
            val ref = GitRef.of(r.refType, r.refValue).describe()
            logger.quiet("${r.url}  [$ref]  ${r.tag?.let { "$it " } ?: ""}@ ${r.commit.take(10)}")
        }
    }
}

abstract class KloneOutdatedTask : DefaultTask() {
    @get:Input abstract val repos: ListProperty<String>
    @get:Input abstract val cacheDir: Property<String>

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun check() {
        val git = GitResolver(File(cacheDir.get()))
        var outdated = 0
        for (r in repos.get().map { KloneTasks.ResolvedRepo.decode(it) }) {
            val line = when (val ref = GitRef.of(r.refType, r.refValue)) {
                is GitRef.Tag, is GitRef.Range -> {
                    val versions = git.remoteTags(r.url).mapNotNull { t -> SemVer.fromTag(t, r.tagPattern)?.let { t to it } }.toMap()
                    val current = r.tag?.let { versions[it] }
                    val latest = SemVer.best("*", versions)
                    val inRange = (ref as? GitRef.Range)?.let { SemVer.best(it.spec, versions) }
                    when {
                        latest == null -> "${r.url}: no version tags found"
                        current != null && versions.getValue(latest) <= current -> null
                        else -> {
                            outdated++
                            "${r.url}: ${r.tag ?: r.refValue} → latest $latest" +
                                (if (inRange != null && inRange != r.tag) " (allowed by ${ref.describe()}: $inRange — run -Pklone.update)" else "")
                        }
                    }
                }
                is GitRef.Branch, GitRef.Default -> {
                    val remote = git.remoteHead(r.url, (ref as? GitRef.Branch)?.name)
                    if (remote == null || remote == r.commit) null
                    else { outdated++; "${r.url}: ${ref.describe()} moved ${r.commit.take(8)} → ${remote.take(8)} (run -Pklone.update)" }
                }
                is GitRef.Commit -> null
            }
            line?.let { logger.lifecycle(it) }
        }
        logger.lifecycle(if (outdated == 0) "All git dependencies are up to date." else "$outdated git dependenc${if (outdated == 1) "y" else "ies"} can be updated.")
    }
}

abstract class KloneCleanTask : DefaultTask() {
    @get:Input abstract val repos: ListProperty<String>
    @get:Input abstract val cacheDir: Property<String>

    @get:Internal
    @set:Option(option = "all", description = "Delete the whole Klone cache, not only this build's repos")
    var all: Boolean = false

    @get:Internal
    @set:Option(option = "unused-days", description = "Delete every cached repo not used for N days")
    var unusedDays: String? = null

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun clean() {
        val root = File(cacheDir.get())
        val targets: List<File> = when {
            all -> listOf(root)
            unusedDays != null -> {
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(unusedDays!!.toLong())
                cachedRepos(root).filter { dir ->
                    val lastUsed = File(dir, ".git/klone-last-used").takeIf { it.exists() }
                        ?.readText()?.trim()?.toLongOrNull() ?: dir.lastModified()
                    lastUsed < cutoff
                }
            }
            else -> repos.get().map { File(KloneTasks.ResolvedRepo.decode(it).localDir) }
        }
        val failed = mutableListOf<File>()
        targets.filter { it.exists() }.forEach { dir ->
            if (dir.deleteTree()) {
                File(dir.parentFile, "${dir.name}.lock").delete()
                logger.lifecycle("Deleted ${dir.absolutePath}")
            } else {
                failed += dir.walkTopDown().filter { it.exists() }.take(5)
            }
        }
        if (failed.isNotEmpty()) throw org.gradle.api.GradleException(
            "[Klone] Could not delete (files in use): ${failed.joinToString { it.absolutePath }}\n" +
                "On Windows, Gradle's file watching keeps included builds open: run with --no-watch-fs, or ./gradlew --stop first."
        )
        if (targets.none()) logger.lifecycle("Nothing to clean.")
    }

    /** Repo checkouts under [dir], without walking into them. */
    private fun cachedRepos(dir: File): List<File> =
        if (File(dir, ".git").isDirectory) listOf(dir)
        else dir.listFiles { f -> f.isDirectory }.orEmpty().flatMap { cachedRepos(it) }
}
