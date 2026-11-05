package dev.klone

import dev.klone.composite.CompositeIncluder
import dev.klone.composite.HostPropagation
import dev.klone.model.GitDependency
import dev.klone.model.LocalDependency
import dev.klone.model.ModuleSelection
import dev.klone.model.gitUrlKey
import dev.klone.resolver.BuildFileScanner
import dev.klone.resolver.GitResolveValueSource
import dev.klone.resolver.KloneGitException
import dev.klone.resolver.LockFileManager
import dev.klone.resolver.ModuleDetector
import dev.klone.resolver.PluginVersions
import dev.klone.tasks.KloneTasks
import dev.klone.resolver.ModuleDetector.SubmoduleInfo
import dev.klone.resolver.toLockEntry
import dev.klone.toml.KloneCatalog
import dev.klone.toml.VersionCatalogReader
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import java.io.File
import java.net.URI

class KloneSettingsPlugin : Plugin<Settings> {

    private val logger = Logging.getLogger(KloneSettingsPlugin::class.java)

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("klone", KloneSettingsExtension::class.java)
        settings.gradle.settingsEvaluated { resolveAll(settings, extension) }
    }

    private fun resolveAll(settings: Settings, extension: KloneSettingsExtension) {
        val rootDir = settings.rootDir
        // The registry outlives builds in the daemon — start clean every time
        KloneRegistry.clear()
        KloneRegistry.rootDir = rootDir

        val catalog = KloneCatalog.parse(extension.catalogFile ?: File(rootDir, KloneCatalog.DEFAULT_PATH))
        val versions = VersionCatalogReader.read(rootDir)
        val gitDeps = BuildFileScanner.scanProject(rootDir, versions) + extension.dependencies + catalog.gitDependencies
        val localDeps = BuildFileScanner.scanProjectForLocal(rootDir) + extension.localDependencies + catalog.localDependencies
        if (gitDeps.isEmpty() && localDeps.isEmpty()) {
            KloneTasks.register(settings, emptyList(), resolveCacheDir(settings, extension))
            return
        }

        val usedBuildNames = mutableSetOf(settings.rootProject.name)
        val cacheDir = resolveCacheDir(settings, extension)
        val repos = resolveGit(settings, gitDeps.distinct(), usedBuildNames, cacheDir, extension.propagatedProperties)
        resolveLocal(settings, localDeps.distinct(), usedBuildNames)
        KloneTasks.register(settings, repos, cacheDir)
    }

    private fun resolveGit(
        settings: Settings,
        deps: List<GitDependency>,
        usedBuildNames: MutableSet<String>,
        cacheDir: File,
        allowedProperties: List<String>
    ): List<KloneTasks.ResolvedRepo> {
        if (deps.isEmpty()) return emptyList()
        val offline = settings.startParameter.isOffline
        val refresh = settings.startParameter.isRefreshDependencies
        val update = UpdateRequest.parse(settings.providers.gradleProperty("klone.update").orNull)
        val frozen = settings.providers.gradleProperty("klone.frozen").orNull == "true"
        if (frozen && update.any) throw GradleException("[Klone] -Pklone.frozen=true and -Pklone.update cannot be combined")
        val lockManager = LockFileManager(settings.rootDir)
        val lockEntries = mutableListOf<LockFileManager.LockEntry>()
        val repos = mutableListOf<KloneTasks.ResolvedRepo>()
        val hostVersions = PluginVersions.detect(settings.rootDir)

        // https://host/a/b, https://host/a/b.git and git@host:a/b are the same repo: one clone, one includeBuild
        val depsByRepo = deps.groupBy { gitUrlKey(it.url).lowercase() }
        logger.lifecycle("[Klone] Resolving ${depsByRepo.size} git repo(s)...")

        if (frozen) {
            val unlocked = depsByRepo.values.map { it.first() }.filter { lockManager.locked(it.url, it.ref) == null }
            if (unlocked.isNotEmpty()) throw GradleException(
                "[Klone] klone.resolved is out of date (-Pklone.frozen=true):\n" +
                    unlocked.joinToString("\n") { "  ${it.url} (${it.ref.describe()}) is not locked" } +
                    "\nRun the build without klone.frozen and commit klone.resolved."
            )
        }

        for (group in depsByRepo.values) {
            val first = group.first()
            val refs = group.map { it.ref }.distinct()
            if (refs.size > 1) {
                throw GradleException(
                    "[Klone] ${first.url} is declared with different refs: ${refs.joinToString { it.describe() }}.\n" +
                        "Use the same version/branch/commit everywhere (one checkout per repo)."
                )
            }
            val dirs = group.map { it.dir }.distinct()
            if (dirs.size > 1) throw GradleException("[Klone] ${first.url} is declared with different `dir` values: $dirs")

            val locked = if (update.matches(first)) null else lockManager.locked(first.url, first.ref)
            val resolved = try {
                GitResolveValueSource.resolve(settings.providers, first, locked, cacheDir, offline, refresh)
            } catch (e: Exception) {
                // ValueSource failures arrive wrapped; surface git's own message
                val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is KloneGitException } ?: e
                throw GradleException("[Klone] Failed to resolve ${first.url} (${first.ref.describe()}): ${cause.message}", cause)
            }
            val pin = if (locked != null) " (locked — run with -Pklone.update=${first.repoName} to update)" else ""
            val tag = resolved.tag?.let { " ($it)" } ?: ""
            logger.lifecycle("[Klone] ${first.url}$tag @ ${resolved.commitHash.take(8)}$pin")

            val buildDir = first.dir?.let { File(resolved.localDir, it) } ?: resolved.localDir
            if (!buildDir.isDirectory) throw GradleException("[Klone] dir \"${first.dir}\" does not exist in ${first.url}")
            HostPropagation.propagate(buildDir, settings, allowedProperties)
            propagateRepoRepositories(settings, buildDir)
            PluginVersions.warnIfIncompatible(hostVersions, buildDir, first.url, logger)

            val selected = includeSource(settings, first.url, buildDir, group, uniqueName(buildDir, usedBuildNames))
            lockEntries += first.ref.toLockEntry(first.url, resolved.commitHash, selected.joinToString(",") { it.coordinates }, resolved.tag)
            repos += KloneTasks.ResolvedRepo(first.url, first.ref.type, first.ref.value, first.tagPattern, resolved.commitHash, resolved.tag, resolved.localDir.absolutePath)
        }

        if (frozen) {
            val drift = lockManager.diff(lockEntries)
            if (drift.isNotEmpty()) throw GradleException(
                "[Klone] klone.resolved is out of date (-Pklone.frozen=true):\n" + drift.joinToString("\n") { "  $it" }
            )
        } else {
            lockManager.write(lockEntries)
        }
        return repos
    }

    /** `-Pklone.update=true` updates everything; `-Pklone.update=sdk,design` only repos whose name or URL match. */
    internal class UpdateRequest(private val all: Boolean, private val names: List<String>) {
        val any: Boolean get() = all || names.isNotEmpty()

        fun matches(dep: GitDependency): Boolean =
            all || names.any { it.equals(dep.repoName, ignoreCase = true) || dep.url.contains(it, ignoreCase = true) }

        companion object {
            fun parse(value: String?): UpdateRequest = when (value?.trim()) {
                null, "", "false" -> UpdateRequest(false, emptyList())
                "true" -> UpdateRequest(true, emptyList())
                else -> UpdateRequest(false, value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
    }

    private fun resolveLocal(settings: Settings, deps: List<LocalDependency>, usedBuildNames: MutableSet<String>) {
        for ((dir, group) in deps.groupBy { KloneRegistry.resolveLocalDir(it.path, settings.rootDir) }) {
            if (!dir.isDirectory) throw GradleException("[Klone] Local dependency path does not exist: ${dir.absolutePath}")
            if (!File(dir, "build.gradle.kts").exists() && !File(dir, "build.gradle").exists()) {
                throw GradleException("[Klone] No build.gradle.kts or build.gradle found at: ${dir.absolutePath}")
            }
            includeSource(settings, KloneRegistry.localKey(dir), dir, group, uniqueName(dir, usedBuildNames))
            logger.lifecycle("[Klone] Included local ${dir.name}")
        }
    }

    /** Detects modules, registers the selected ones and adds one includeBuild with their substitutions. */
    private fun includeSource(
        settings: Settings,
        key: String,
        dir: File,
        selections: List<ModuleSelection>,
        buildName: String
    ): List<SubmoduleInfo> {
        val detected = ModuleDetector.detectAllSubmodules(dir)
        when {
            detected.isEmpty() ->
                logger.warn("[Klone] No modules detected in ${dir.name} — check that build.gradle(.kts) and settings.gradle(.kts) exist")
            detected.size == 1 && detected[0].moduleName == null ->
                logger.info("[Klone] ${dir.name}: single-module (${detected[0].coordinates})")
            else ->
                logger.info("[Klone] ${dir.name}: ${detected.size} modules — ${detected.map { it.moduleName ?: "root" }}")
        }

        val registered = mutableListOf<SubmoduleInfo>()
        for (selection in selections) {
            for (info in select(detected, selection, dir)) {
                // Explicit coordinates register under the root key, like the settings DSL always did
                val name = if (selection.moduleCoordinates != null) null else info.moduleName
                KloneRegistry.register(key, name, KloneRegistry.Entry(key, info.coordinates, dir))
                registered += info
            }
        }

        val distinct = registered.distinctBy { it.coordinates }
        CompositeIncluder.includeAll(
            settings, dir,
            distinct.map { CompositeIncluder.ModuleSubstitution(it.coordinates, it.projectPath) },
            buildName
        )
        return distinct
    }

    internal fun select(detected: List<SubmoduleInfo>, selection: ModuleSelection, dir: File): List<SubmoduleInfo> {
        val names = selection.submoduleNames
        fun matches(info: SubmoduleInfo, name: String) =
            info.moduleName == name || info.projectPath == name || info.projectPath == ":$name" ||
                info.projectPath.substringAfterLast(':') == name

        selection.moduleCoordinates?.let { coords ->
            // Known coordinates (settings DSL / version catalog): find the project that builds them
            val artifact = names?.singleOrNull() ?: coords.split(':').getOrNull(1)
            val project = detected.firstOrNull { artifact != null && matches(it, artifact) }?.projectPath ?: ":"
            return listOf(SubmoduleInfo(project, coords.split(':').take(2).joinToString(":"), artifact))
        }
        return when {
            names == null -> detected
            names.isEmpty() -> detected.filter { it.projectPath == ":" }
            else -> names.map { name ->
                detected.firstOrNull { matches(it, name) } ?: throw GradleException(
                    "[Klone] Module '$name' not found in ${dir.name}. Available: ${detected.map { it.moduleName ?: "root" }}"
                )
            }
        }
    }

    /** Repos the cloned build declares itself (e.g. JitPack) so its transitive deps resolve in the host. */
    private fun propagateRepoRepositories(settings: Settings, dir: File) {
        val extraRepos = ModuleDetector.extractMavenUrls(dir)
        if (extraRepos.isEmpty()) return
        val existing = settings.dependencyResolutionManagement.repositories
            .filterIsInstance<org.gradle.api.artifacts.repositories.MavenArtifactRepository>()
            .map { it.url.toString().trimEnd('/') }
            .toSet()
        val missing = extraRepos.filter { it.trimEnd('/') !in existing }
        if (missing.isEmpty()) return
        logger.lifecycle("[Klone] Propagating ${missing.size} repository(ies) from ${dir.name}")
        settings.dependencyResolutionManagement.repositories { handler ->
            missing.forEach { url -> handler.maven { it.url = URI(url) } }
        }
    }

    private fun uniqueName(dir: File, used: MutableSet<String>): String {
        val candidates = sequenceOf(dir.name, "${dir.parentFile?.name}-${dir.name}") + generateSequence(2) { it + 1 }.map { "${dir.name}-$it" }
        return candidates.first { used.add(it) }
    }

    /** `KLONE_CACHE_DIR` > `klone { cacheDir }` > `-Pklone.cacheDir` > `$GRADLE_USER_HOME/caches/klone`. */
    private fun resolveCacheDir(settings: Settings, extension: KloneSettingsExtension): File {
        settings.providers.environmentVariable("KLONE_CACHE_DIR").orNull?.let { return File(it) }
        extension.cacheDir?.let { return it }
        settings.providers.gradleProperty("klone.cacheDir").orNull?.let { return File(it) }
        // Inside Gradle's caches: cleared with them, and saved by CI Gradle caching (e.g. gradle/actions/setup-gradle)
        return File(settings.gradle.gradleUserHomeDir, "caches/klone")
    }
}
