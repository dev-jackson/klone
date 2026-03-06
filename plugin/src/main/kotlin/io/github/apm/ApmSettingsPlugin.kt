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

                logger.lifecycle("[Klone] Downloaded ${resolved.localDir.name} @ ${resolved.commitHash.take(8)}")

                injectSdkDir(resolved.localDir, settings.rootDir)
                injectGradleProperties(resolved.localDir, settings.rootDir)
                injectHostRepositories(resolved.localDir, settings.rootDir)

                val extraRepos = ModuleDetector.extractMavenUrls(resolved.localDir)
                if (extraRepos.isNotEmpty()) {
                    logger.lifecycle("[Klone] Propagating ${extraRepos.size} repository(ies) from ${resolved.localDir.name}")
                    settings.dependencyResolutionManagement.repositories { handler ->
                        for (url in extraRepos) {
                            handler.maven { repo -> repo.url = java.net.URI(url) }
                        }
                    }
                }

                val allSubmodules = ModuleDetector.detectAllSubmodules(resolved.localDir)

                when {
                    allSubmodules.isEmpty() ->
                        logger.warn("[Klone] No modules detected in ${resolved.localDir.name} — check that build.gradle.kts and settings.gradle.kts exist")
                    allSubmodules.size == 1 && allSubmodules[0].moduleName == null ->
                        logger.lifecycle("[Klone] ${resolved.localDir.name}: single-module (${allSubmodules[0].coordinates})")
                    else ->
                        logger.lifecycle("[Klone] ${resolved.localDir.name}: ${allSubmodules.size} modules detected — ${allSubmodules.map { it.moduleName ?: "root" }}")
                }
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

    internal fun injectSdkDir(localDir: File, hostRootDir: File) {
        val clonedProps = File(localDir, "local.properties")
        if (clonedProps.exists() && clonedProps.readText().contains("sdk.dir")) return

        val sdkLine = readSdkDirFromHost(hostRootDir) ?: run {
            logger.warn("[Klone] No sdk.dir found in host local.properties — skipping injection for ${localDir.name}")
            return
        }

        if (clonedProps.exists()) {
            clonedProps.appendText("\n$sdkLine\n")
        } else {
            clonedProps.writeText("# Propagated by Klone from host project\n$sdkLine\n")
        }
        logger.lifecycle("[Klone] Propagated sdk.dir into ${localDir.name}/local.properties")
    }

    internal fun readSdkDirFromHost(hostRootDir: File): String? {
        val hostProps = File(hostRootDir, "local.properties")
        if (!hostProps.exists()) return null
        return Regex("""^sdk\.dir=.+$""", RegexOption.MULTILINE)
            .find(hostProps.readText())
            ?.value
    }

    internal fun injectGradleProperties(localDir: File, hostRootDir: File) {
        val merged = java.util.Properties()
        hostRootDir.listFiles { f -> f.isFile && f.name.endsWith(".properties") }
            ?.sortedBy { it.name }
            ?.forEach { f -> f.inputStream().use { merged.load(it) } }
        if (merged.isEmpty) return

        val clonedPropsFile = File(localDir, "gradle.properties")
        val existingContent = if (clonedPropsFile.exists()) clonedPropsFile.readText() else ""

        // Original content = everything outside the sentinel block (never modified)
        val originalContent = removeSentinelBlock(existingContent)
        val originalKeys: Set<String> = java.util.Properties().also { p ->
            if (originalContent.isNotBlank()) p.load(originalContent.reader())
        }.stringPropertyNames()

        val toInject = merged.stringPropertyNames()
            .filter { it !in originalKeys }
            .sorted()

        if (toInject.isEmpty()) {
            // Clean up stale sentinel block if nothing to inject anymore
            if (existingContent != originalContent) clonedPropsFile.writeText(originalContent)
            return
        }

        val lines = toInject.joinToString("\n") { key -> "$key=${merged.getProperty(key)}" }
        val sentinelBlock = "# <klone-injected>\n$lines\n# </klone-injected>"

        val newContent = when {
            existingContent.contains("# <klone-injected>") ->
                replaceSentinelBlock(existingContent, sentinelBlock)
            originalContent.isNotEmpty() ->
                originalContent.trimEnd { it == '\n' } + "\n\n$sentinelBlock\n"
            else -> "$sentinelBlock\n"
        }

        clonedPropsFile.writeText(newContent)
        logger.lifecycle("[Klone] Propagated ${toInject.size} gradle.properties entr${if (toInject.size == 1) "y" else "ies"} into ${localDir.name}")
    }

    internal fun injectHostRepositories(localDir: File, hostRootDir: File) {
        val hostSettingsFile = File(hostRootDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(hostRootDir, "settings.gradle").takeIf { it.exists() }
            ?: return

        val hostContent = hostSettingsFile.readText()
        val privateMavenBlocks = extractMavenBlocks(hostContent).filter { block ->
            val url = extractUrlFromMavenBlock(block)
            url != null && !isPublicRepo(url)
        }
        if (privateMavenBlocks.isEmpty()) return

        val clonedSettingsFile = File(localDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(localDir, "settings.gradle").takeIf { it.exists() }
            ?: return

        val clonedContent = clonedSettingsFile.readText()
        val reposBlock = privateMavenBlocks.joinToString("\n") { "        $it" }
        val sentinelBlock = """// <klone-repos>
dependencyResolutionManagement {
    repositories {
$reposBlock
    }
}
// </klone-repos>"""

        val startTag = "// <klone-repos>"
        val endTag = "// </klone-repos>"

        val newContent = if (clonedContent.contains(startTag)) {
            val start = clonedContent.indexOf(startTag)
            val end = clonedContent.indexOf(endTag)
            if (end == -1) clonedContent else
                clonedContent.substring(0, start) + sentinelBlock + clonedContent.substring(end + endTag.length)
        } else {
            clonedContent.trimEnd { it == '\n' } + "\n\n$sentinelBlock\n"
        }

        clonedSettingsFile.writeText(newContent)
        logger.lifecycle("[Klone] Propagated ${privateMavenBlocks.size} private Maven repo(s) into ${localDir.name}/settings")
    }

    internal fun extractMavenBlocks(content: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("""maven\s*\{""")
        for (match in regex.findAll(content)) {
            val start = match.range.first
            var depth = 0
            var end = start
            for (i in match.range.last..content.lastIndex) {
                if (content[i] == '{') depth++
                else if (content[i] == '}') {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
            result.add(content.substring(start, end + 1))
        }
        return result
    }

    internal fun extractUrlFromMavenBlock(block: String): String? {
        val match = Regex("""url\s*=?\s*(?:uri\s*\(\s*)?["']([^"']+)["']""").find(block)
        return match?.groupValues?.get(1)
    }

    private fun isPublicRepo(url: String): Boolean {
        val publicPrefixes = listOf(
            "https://dl.google.com/dl/android/maven2",
            "https://repo1.maven.org/maven2",
            "https://repo.maven.apache.org/maven2",
            "https://jcenter.bintray.com",
            "https://plugins.gradle.org/m2",
        )
        return publicPrefixes.any { url.startsWith(it) }
    }

    private fun removeSentinelBlock(content: String): String {
        val startTag = "# <klone-injected>"
        val endTag = "# </klone-injected>"
        val start = content.indexOf(startTag)
        val end = content.indexOf(endTag)
        if (start == -1 || end == -1) return content
        val before = content.substring(0, start).trimEnd { it == '\n' }
        val after = content.substring(end + endTag.length).trimStart { it == '\n' }
        return buildString {
            if (before.isNotEmpty()) append(before).append('\n')
            append(after)
        }
    }

    private fun replaceSentinelBlock(content: String, newBlock: String): String {
        val startTag = "# <klone-injected>"
        val endTag = "# </klone-injected>"
        val start = content.indexOf(startTag)
        val end = content.indexOf(endTag)
        if (start == -1 || end == -1) return content
        return content.substring(0, start) + newBlock + content.substring(end + endTag.length)
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
