package dev.klone

import dev.klone.composite.CompositeIncluder
import dev.klone.model.GitDependency
import dev.klone.model.LocalDependency
import dev.klone.resolver.BuildFileScanner
import dev.klone.resolver.GitResolver
import dev.klone.resolver.LockFileManager
import dev.klone.resolver.ModuleDetector
import dev.klone.resolver.toLockEntry
import org.gradle.api.Plugin
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
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
                injectHostRepositories(resolved.localDir, settings)

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

            // --- Local dependencies ---
            val scannedLocal = BuildFileScanner.scanProjectForLocal(settings.rootDir)
            val explicitLocal = extension.localDependencies
            val allLocalDeps = (scannedLocal + explicitLocal).distinctBy { it.path }

            if (allLocalDeps.isNotEmpty()) {
                logger.lifecycle("[Klone] Resolving ${allLocalDeps.size} local dependency declaration(s)...")
            }

            for (dep in allLocalDeps) {
                val localDir = resolveLocalPath(dep.path, settings.rootDir)

                require(localDir.exists()) {
                    "Local dependency path does not exist: ${localDir.absolutePath}"
                }
                require(hasBuildFile(localDir)) {
                    "No build.gradle.kts or build.gradle found at: ${localDir.absolutePath}"
                }

                val registryKey = "local:${localDir.absolutePath}"

                val allSubmodules = ModuleDetector.detectAllSubmodules(localDir)

                when {
                    allSubmodules.isEmpty() ->
                        logger.warn("[Klone] No modules detected in ${localDir.name} — check that build.gradle.kts and settings.gradle.kts exist")
                    allSubmodules.size == 1 && allSubmodules[0].moduleName == null ->
                        logger.lifecycle("[Klone] ${localDir.name}: single-module (${allSubmodules[0].coordinates})")
                    else ->
                        logger.lifecycle("[Klone] ${localDir.name}: ${allSubmodules.size} modules detected — ${allSubmodules.map { it.moduleName ?: "root" }}")
                }

                val substitutions = mutableListOf<CompositeIncluder.ModuleSubstitution>()

                if (dep.moduleCoordinates != null) {
                    KloneRegistry.register(registryKey, null, KloneRegistry.Entry(
                        url = registryKey,
                        moduleCoordinates = dep.moduleCoordinates,
                        localDir = localDir
                    ))
                    substitutions.add(CompositeIncluder.ModuleSubstitution(dep.moduleCoordinates, ":"))
                } else {
                    val targetSubmodules = when {
                        dep.submoduleNames == null -> allSubmodules
                        dep.submoduleNames.isEmpty() -> allSubmodules.filter { it.projectPath == ":" }
                        else -> allSubmodules.filter { it.moduleName in dep.submoduleNames }
                    }

                    for (info in targetSubmodules) {
                        KloneRegistry.register(registryKey, info.moduleName, KloneRegistry.Entry(
                            url = registryKey,
                            moduleCoordinates = info.coordinates,
                            localDir = localDir
                        ))
                        substitutions.add(CompositeIncluder.ModuleSubstitution(info.coordinates, info.projectPath))
                    }
                }

                val distinctSubs = substitutions.distinctBy { it.coordinates }
                CompositeIncluder.includeAll(settings, localDir, distinctSubs)
                logger.lifecycle("[Klone] Included local ${localDir.name} (${distinctSubs.size} module substitution(s))")
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

    internal fun injectHostRepositories(localDir: File, settings: Settings) {
        val repos = settings.dependencyResolutionManagement.repositories
            .filterIsInstance<MavenArtifactRepository>()
            .filter { !isPublicRepo(it.url.toString()) }
        if (repos.isEmpty()) return

        val clonedSettingsFile = File(localDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(localDir, "settings.gradle").takeIf { it.exists() }
            ?: return

        val repoBlocks = repos.map { repo ->
            val url = repo.url.toString()
            val creds = repo.credentials
            val user = creds.username
            val pass = creds.password
            buildString {
                appendLine("        maven {")
                appendLine("            url = uri(\"$url\")")
                if (user != null || pass != null) {
                    appendLine("            credentials {")
                    if (user != null) appendLine("                username = \"$user\"")
                    if (pass != null) appendLine("                password = \"$pass\"")
                    appendLine("            }")
                }
                append("        }")
            }
        }

        val sentinelBlock = buildString {
            appendLine("// <klone-repos>")
            appendLine("dependencyResolutionManagement {")
            appendLine("    repositories {")
            repoBlocks.forEach { appendLine(it) }
            appendLine("    }")
            appendLine("}")
            append("// </klone-repos>")
        }

        writeSettingsSentinel(clonedSettingsFile, sentinelBlock)
        logger.lifecycle("[Klone] Propagated ${repos.size} private Maven repo(s) into ${localDir.name}/settings")
    }

    internal companion object {
        fun writeSettingsSentinel(file: File, sentinelBlock: String) {
            val content = file.readText()
            val startTag = "// <klone-repos>"
            val endTag = "// </klone-repos>"
            val newContent = if (content.contains(startTag)) {
                val start = content.indexOf(startTag)
                val end = content.indexOf(endTag)
                if (end == -1) content else
                    content.substring(0, start) + sentinelBlock + content.substring(end + endTag.length)
            } else {
                content.trimEnd { it == '\n' } + "\n\n$sentinelBlock\n"
            }
            file.writeText(newContent)
        }
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

    private fun resolveLocalPath(path: String, rootDir: File): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(rootDir, path).canonicalFile
    }

    private fun hasBuildFile(dir: File): Boolean =
        File(dir, "build.gradle.kts").exists() || File(dir, "build.gradle").exists()

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
