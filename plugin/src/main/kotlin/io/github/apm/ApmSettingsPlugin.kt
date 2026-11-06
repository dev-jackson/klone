package dev.klone

import dev.klone.composite.CompositeIncluder
import dev.klone.composite.HostPropagation
import dev.klone.composite.ModuleTrimmer
import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.LocalDependency
import dev.klone.model.SemVer
import dev.klone.model.ModuleSelection
import dev.klone.model.gitUrlKey
import dev.klone.resolver.BuildFileScanner
import dev.klone.resolver.GitResolveValueSource
import dev.klone.resolver.GitResolver
import dev.klone.resolver.VersionSolver
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
import org.gradle.build.event.BuildEventsListenerRegistry
import javax.inject.Inject
import java.io.File
import java.net.URI

abstract class KloneSettingsPlugin @Inject constructor(
    private val buildEvents: BuildEventsListenerRegistry
) : Plugin<Settings> {

    private val logger = Logging.getLogger(KloneSettingsPlugin::class.java)

    private companion object {
        const val HOST = "host"
        const val MAX_GRAPH_ROUNDS = 10
    }

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("klone", KloneSettingsExtension::class.java)
        settings.gradle.settingsEvaluated { resolveAll(settings, extension) }
    }

    private fun resolveAll(settings: Settings, extension: KloneSettingsExtension) {
        val rootDir = settings.rootDir
        // The registry outlives builds in the daemon — start clean every time. Scoped per build:
        // cloned repos that use Klone themselves are included builds evaluated after this one.
        val scope = KloneRegistry.reset(rootDir)
        ProjectBinding.register(settings.gradle, rootDir)
        // A cloned repo using Klone is an included build: the root build already resolved the whole graph
        val rootScope = if (settings.gradle.parent != null) KloneRegistry.rootScope(settings.gradle) else null
        if (settings.gradle.parent == null) KloneRegistry.registerRoot(settings.gradle, scope)

        val catalog = KloneCatalog.parse(extension.catalogFile ?: File(rootDir, KloneCatalog.DEFAULT_PATH))
        val versions = VersionCatalogReader.read(rootDir)
        val gitDeps = BuildFileScanner.scanProject(rootDir, versions) + extension.dependencies + catalog.gitDependencies
        val localDeps = BuildFileScanner.scanProjectForLocal(rootDir) + extension.localDependencies + catalog.localDependencies
        if (gitDeps.isEmpty() && localDeps.isEmpty()) {
            KloneTasks.register(settings, emptyList(), resolveCacheDir(settings, extension))
            return
        }

        val cacheDir = resolveCacheDir(settings, extension)
        // `./gradlew kloneClean` must not include the builds it deletes (Windows keeps their .gradle/ open)
        if (onlyCleaning(settings)) {
            scope.resolutionSkipped = true
            KloneTasks.register(settings, gitDeps.distinct().map { dep ->
                KloneTasks.ResolvedRepo(dep.url, dep.ref.type, dep.ref.value, dep.tagPattern, "", null, File(cacheDir, dep.cacheKey).absolutePath)
            }.distinctBy { it.localDir }, cacheDir)
            return
        }
        val (fromRoot, own) = if (rootScope == null) emptyList<GitDependency>() to gitDeps.distinct()
            else gitDeps.distinct().partition { rootScope.resolution(it.url) != null }
        fromRoot.forEach { reuseRootResolution(settings, rootScope!!, scope, it) }
        if (rootScope != null && own.isNotEmpty()) logger.warn(
            "[Klone] ${settings.rootProject.name} resolves ${own.joinToString { it.url }} on its own: the host cannot see " +
                "these declarations (settings DSL or variables). Declare them in build files or [metadata] so the host resolves one version for everyone."
        )
        val usedBuildNames = mutableSetOf(settings.rootProject.name)
        val repos = resolveGit(settings, own, usedBuildNames, cacheDir, extension.propagatedProperties, scope)
        resolveLocal(settings, localDeps.distinct(), usedBuildNames)
        KloneTasks.register(settings, repos, cacheDir)
        if (repos.isNotEmpty()) watchMetaspace(settings, repos.size)
    }

    /** Warns before a too small `-XX:MaxMetaspaceSize` hangs the build (see [MetaspaceAdvisor]). */
    private fun watchMetaspace(settings: Settings, repos: Int) {
        val max = MetaspaceAdvisor.current()?.second ?: return
        MetaspaceAdvisor.estimated(repos, max)?.let { logger.warn(it) }
        val warnPercent = settings.providers.gradleProperty("klone.metaspaceWarnPercent").orNull?.toIntOrNull() ?: 80
        val check = settings.gradle.sharedServices.registerIfAbsent("kloneMetaspace", MetaspaceCheck::class.java) {
            it.parameters.warnPercent.set(warnPercent)
        }
        // A completion listener keeps the service alive for the whole build; it is closed (and checks) at the end
        buildEvents.onTaskCompletion(check)
    }

    private fun resolveGit(
        settings: Settings,
        deps: List<GitDependency>,
        usedBuildNames: MutableSet<String>,
        cacheDir: File,
        allowedProperties: List<String>,
        scope: KloneRegistry.Scope
    ): List<KloneTasks.ResolvedRepo> {
        if (deps.isEmpty()) return emptyList()
        val update = UpdateRequest.parse(settings.providers.gradleProperty("klone.update").orNull)
        val frozen = settings.providers.gradleProperty("klone.frozen").orNull == "true"
        val allModules = settings.providers.gradleProperty("klone.allModules").orNull == "true"
        if (frozen && update.any) throw GradleException("[Klone] -Pklone.frozen=true and -Pklone.update cannot be combined")
        val lockManager = LockFileManager(settings.rootDir)
        val hostVersions = PluginVersions.detect(settings.rootDir)

        val plan = planGraph(settings, deps, cacheDir, lockManager, update)
        logger.lifecycle("[Klone] Resolving ${plan.size} git repo(s)...")
        if (frozen) {
            val unlocked = plan.filter { it.locked == null }
            if (unlocked.isNotEmpty()) throw GradleException(
                "[Klone] klone.resolved is out of date (-Pklone.frozen=true):\n" +
                    unlocked.joinToString("\n") { "  ${it.dep.url} (${it.dep.ref.describe()}) is not locked" } +
                    "\nRun the build without klone.frozen and commit klone.resolved."
            )
        }

        val lockEntries = mutableListOf<LockFileManager.LockEntry>()
        val repos = mutableListOf<KloneTasks.ResolvedRepo>()
        for (repo in plan) {
            val dep = repo.dep
            val resolved = repo.resolved
            val pin = if (repo.locked != null) " (locked — run with -Pklone.update=${dep.repoName} to update)" else ""
            val tag = resolved.tag?.let { " ($it)" } ?: ""
            val via = repo.requirements.map { it.requiredBy }.distinct().filter { it != HOST }.takeIf { it.isNotEmpty() }
                ?.let { " (required by ${it.joinToString()})" } ?: ""
            logger.lifecycle("[Klone] ${dep.url}$tag @ ${resolved.commitHash.take(8)}$pin$via")

            HostPropagation.propagate(repo.buildDir, settings, allowedProperties)
            propagateRepoRepositories(settings, repo.buildDir)
            PluginVersions.warnIfIncompatible(hostVersions, repo.buildDir, dep.url, logger)

            // Modules any dependent selects: the host's and those nested repos use
            val selections = repo.requirements.map { it.dep }
            val selected = includeSource(settings, dep.url, repo.buildDir, selections, uniqueName(repo.buildDir, usedBuildNames))
            val skipped = ModuleTrimmer.trim(repo.buildDir, if (allModules) null else selected.map { it.projectPath })
            if (skipped.isNotEmpty()) logger.info("[Klone] ${displayName(repo.buildDir)}: ${skipped.size} unused module(s) configured empty: $skipped")
            scope.resolved(dep.url, KloneRegistry.Resolution(resolved.commitHash, resolved.tag))
            lockEntries += dep.ref.toLockEntry(dep.url, resolved.commitHash, selected.joinToString(",") { it.coordinates }, resolved.tag)
            repos += KloneTasks.ResolvedRepo(dep.url, dep.ref.type, dep.ref.value, dep.tagPattern, resolved.commitHash, resolved.tag,
                resolved.localDir.absolutePath, repo.buildDir.absolutePath)
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

    /**
     * Uses the version the root build resolved for [dep] — after checking that [dep]'s own requirement
     * accepts it — instead of resolving (and moving the shared checkout) again.
     */
    private fun reuseRootResolution(settings: Settings, rootScope: KloneRegistry.Scope, scope: KloneRegistry.Scope, dep: GitDependency) {
        val resolution = rootScope.resolution(dep.url)!!
        val version = resolution.tag?.let { SemVer.fromTag(it, dep.tagPattern) ?: SemVer.parse(it) }
        val accepted = when (val ref = dep.ref) {
            is GitRef.Tag -> resolution.tag == ref.name || (version != null && version == SemVer.parse(ref.name))
            is GitRef.Range -> version != null && SemVer.matcher(ref.spec)(version)
            is GitRef.Commit -> resolution.commit.startsWith(ref.hash)
            is GitRef.Branch, GitRef.Default -> true
        }
        if (!accepted) throw GradleException(
            "[Klone] ${settings.rootProject.name} requires ${dep.url} (${dep.ref.describe()}), but the build uses " +
                "${resolution.tag ?: resolution.commit.take(8)} (resolved by the host).\n" +
                "Declare it where the host can see it (build files or [metadata] klone.*) so one version satisfies everyone."
        )
        rootScope.entriesFor(dep.url).forEach { (name, entry) -> scope.register(dep.url, name, entry) }
    }

    /** A repo of the graph: the requirements on it, the single ref they combine to, and its checkout. */
    private class PlannedRepo(
        val requirements: List<VersionSolver.Requirement>,
        val dep: GitDependency,
        val locked: LockFileManager.LockedDependency?,
        val resolved: GitResolver.ResolvedDependency,
        val buildDir: File
    )

    /**
     * The whole graph, like SPM: resolve the host's repos, read what the cloned repos declare with Klone
     * themselves, combine every requirement on a repo into one ref, and repeat until nothing changes.
     * Nested builds then reuse this resolution instead of moving shared checkouts on their own.
     */
    private fun planGraph(
        settings: Settings,
        direct: List<GitDependency>,
        cacheDir: File,
        lockManager: LockFileManager,
        update: UpdateRequest
    ): List<PlannedRepo> {
        val offline = settings.startParameter.isOffline
        val refresh = settings.startParameter.isRefreshDependencies
        val resolutions = mutableMapOf<Pair<String, GitRef>, Pair<LockFileManager.LockedDependency?, GitResolver.ResolvedDependency>>()
        // Ref each shared checkout is on now: a cached resolution is only valid while it still is
        val checkedOut = mutableMapOf<String, GitRef>()
        val declared = mutableMapOf<File, List<GitDependency>>()
        val hostRequirements = direct.map { VersionSolver.Requirement(it, HOST) }
        var requirements = hostRequirements

        repeat(MAX_GRAPH_ROUNDS) {
            // https://host/a/b, https://host/a/b.git and git@host:a/b are the same repo: one clone, one includeBuild
            val plan = requirements.groupBy { gitUrlKey(it.dep.url).lowercase() }.values.map { group ->
                val dep = VersionSolver.combine(group)
                val key = gitUrlKey(dep.url).lowercase()
                if (checkedOut[key] != dep.ref) resolutions.remove(key to dep.ref)
                checkedOut[key] = dep.ref
                val (locked, resolved) = resolutions.getOrPut(key to dep.ref) {
                    val locked = if (update.matches(dep)) null else lockManager.locked(dep.url, dep.ref)
                    val resolved = try {
                        GitResolveValueSource.resolve(settings.providers, dep, locked, cacheDir, offline, refresh)
                    } catch (e: Exception) {
                        // ValueSource failures arrive wrapped; surface git's own message
                        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is KloneGitException } ?: e
                        if (group.size > 1 && dep.ref is GitRef.Range) throw VersionSolver.noVersion(dep.url, group, cause.message)
                        throw GradleException("[Klone] Failed to resolve ${dep.url} (${dep.ref.describe()}): ${cause.message}", cause)
                    }
                    locked to resolved
                }
                val buildDir = dep.dir?.let { File(resolved.localDir, it) } ?: resolved.localDir
                if (!buildDir.isDirectory) throw GradleException("[Klone] dir \"${dep.dir}\" does not exist in ${dep.url}")
                PlannedRepo(group, dep, locked, resolved, buildDir)
            }
            // What the checked-out versions declare (a newer version may declare something else)
            val nested = plan.flatMap { repo ->
                declared.getOrPut(File(repo.buildDir, repo.resolved.commitHash)) { declarationsOf(repo.buildDir) }
                    .map { VersionSolver.Requirement(it, displayName(repo.resolved.localDir)) }
            }
            val next = hostRequirements + nested
            if (next.toSet() == requirements.toSet()) return plan
            requirements = next
        }
        throw GradleException("[Klone] Dependency graph did not settle after $MAX_GRAPH_ROUNDS rounds: requirements keep changing between versions")
    }

    /** Git dependencies a cloned repo declares with Klone (build files and `[metadata] klone.*`). */
    private fun declarationsOf(dir: File): List<GitDependency> =
        BuildFileScanner.scanProject(dir, VersionCatalogReader.read(dir)) +
            KloneCatalog.parse(File(dir, KloneCatalog.DEFAULT_PATH)).gitDependencies

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
                KloneRegistry.scope(settings.rootDir)!!.register(key, name, KloneRegistry.Entry(key, info.coordinates, dir))
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
                    "[Klone] Module '$name' not found in ${displayName(dir)}. Available: ${detected.map { it.moduleName ?: "root" }}"
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

    /** Cache dirs of local remotes carry a hash suffix (`kit-1a2b3c4d`); show the repo name. */
    private fun displayName(dir: File): String = dir.name.replace(Regex("-[0-9a-f]{8}$"), "")

    private fun onlyCleaning(settings: Settings): Boolean {
        val tasks = settings.startParameter.taskNames.filterNot { it.startsWith("-") }
        return tasks.isNotEmpty() && tasks.all { it.removePrefix(":") == "kloneClean" }
    }

    /** `KLONE_CACHE_DIR` > `klone { cacheDir }` > `-Pklone.cacheDir` > `$GRADLE_USER_HOME/caches/klone`. */
    private fun resolveCacheDir(settings: Settings, extension: KloneSettingsExtension): File {
        // Relative paths are relative to where Gradle was invoked (as a shell would), never to the
        // daemon's working directory; the top-level build's, so nested Klone builds share the cache
        val invokedFrom = generateSequence(settings.gradle) { it.parent }.last().startParameter.currentDir
        fun absolute(path: String) = File(path).let { if (it.isAbsolute) it else File(invokedFrom, path) }.normalize()
        settings.providers.environmentVariable("KLONE_CACHE_DIR").orNull?.let { return absolute(it) }
        extension.cacheDir?.let { return it }
        settings.providers.gradleProperty("klone.cacheDir").orNull?.let { return absolute(it) }
        // Inside Gradle's caches: cleared with them, and saved by CI Gradle caching (e.g. gradle/actions/setup-gradle)
        return File(settings.gradle.gradleUserHomeDir, "caches/klone")
    }
}
