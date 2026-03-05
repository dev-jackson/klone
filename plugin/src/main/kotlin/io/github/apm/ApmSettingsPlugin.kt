package dev.klone

import dev.klone.composite.CompositeIncluder
import dev.klone.model.GitDependency
import dev.klone.resolver.BuildFileScanner
import dev.klone.resolver.GitResolver
import dev.klone.resolver.LockFileManager
import dev.klone.resolver.ModuleDetector
import dev.klone.resolver.toLockEntry
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import java.io.File

class KloneSettingsPlugin : Plugin<Settings> {

    private val logger = Logging.getLogger(KloneSettingsPlugin::class.java)

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("klone", KloneSettingsExtension::class.java)

        settings.gradle.settingsEvaluated {
            val cacheDir = resolveCacheDir()
            val resolver = GitResolver(cacheDir)
            val lockManager = LockFileManager(settings.rootDir)
            val lockEntries = mutableListOf<LockFileManager.LockEntry>()

            val scanned = BuildFileScanner.scanProject(settings.rootDir)
            val explicit = extension.dependencies
            val allDeps = mergeUnique(scanned, explicit)

            if (allDeps.isEmpty()) return@settingsEvaluated

            logger.lifecycle("[Klone] Resolving ${allDeps.size} git dependency declaration(s)...")

            // Group by URL — one clone and one includeBuild per URL
            val depsByUrl = allDeps.groupBy { it.url }

            for ((url, urlDeps) in depsByUrl) {
                logger.lifecycle("[Klone] Resolving $url (${urlDeps.first().ref.describe()})")

                val resolved = try {
                    resolver.resolve(urlDeps.first())
                } catch (e: Exception) {
                    logger.error("[Klone] Failed to resolve $url: ${e.message}")
                    throw e
                }

                val allSubmodules = ModuleDetector.detectAllSubmodules(resolved.localDir)
                val substitutions = mutableListOf<CompositeIncluder.ModuleSubstitution>()

                for (dep in urlDeps) {
                    // Explicit Maven coordinates override auto-detection
                    if (dep.moduleCoordinates != null) {
                        KloneRegistry.register(dep.url, null, KloneRegistry.Entry(
                            url = dep.url,
                            moduleCoordinates = dep.moduleCoordinates,
                            localDir = resolved.localDir
                        ))
                        substitutions.add(CompositeIncluder.ModuleSubstitution(dep.moduleCoordinates, ":"))
                        lockEntries.add(dep.ref.toLockEntry(dep.url, resolved.commitHash, dep.moduleCoordinates))
                        continue
                    }

                    val targetSubmodules = when {
                        dep.submoduleNames == null -> allSubmodules
                        dep.submoduleNames.isEmpty() -> allSubmodules.filter { it.projectPath == ":" }
                        else -> allSubmodules.filter { it.moduleName in dep.submoduleNames }
                    }

                    if (targetSubmodules.isEmpty()) {
                        logger.warn("[Klone] No submodules found for $url — skipping.")
                        continue
                    }

                    for (info in targetSubmodules) {
                        KloneRegistry.register(dep.url, info.moduleName, KloneRegistry.Entry(
                            url = dep.url,
                            moduleCoordinates = info.coordinates,
                            localDir = resolved.localDir
                        ))
                        substitutions.add(CompositeIncluder.ModuleSubstitution(info.coordinates, info.projectPath))
                    }

                    lockEntries.add(dep.ref.toLockEntry(
                        dep.url,
                        resolved.commitHash,
                        targetSubmodules.joinToString(",") { it.coordinates }
                    ))
                }

                val distinctSubs = substitutions.distinctBy { it.coordinates }
                CompositeIncluder.includeAll(settings, resolved.localDir, distinctSubs)
                logger.lifecycle("[Klone] Included ${resolved.localDir.name} @ ${resolved.commitHash.take(8)}" +
                    " (${distinctSubs.size} module substitution(s))")
            }

            if (lockEntries.isNotEmpty()) {
                lockManager.write(lockEntries)
            }
        }
    }

    private fun mergeUnique(scanned: List<GitDependency>, explicit: List<GitDependency>): List<GitDependency> {
        val keyFor = { dep: GitDependency -> dep.url + "#" + dep.submoduleNames?.joinToString(",") }
        val map = LinkedHashMap<String, GitDependency>()
        scanned.forEach { map[keyFor(it)] = it }
        explicit.forEach { map[keyFor(it)] = it }
        return map.values.toList()
    }

    private fun resolveCacheDir(): File {
        val envOverride = System.getenv("KLONE_CACHE_DIR")
        return if (envOverride != null) File(envOverride)
        else File(System.getProperty("user.home"), ".klone/packages")
    }
}

private fun dev.klone.model.GitRef.describe(): String = when (this) {
    is dev.klone.model.GitRef.Tag -> "tag: $name"
    is dev.klone.model.GitRef.Branch -> "branch: $name"
    is dev.klone.model.GitRef.Commit -> "commit: ${hash.take(8)}"
}
