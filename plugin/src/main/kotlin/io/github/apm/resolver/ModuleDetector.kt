package dev.klone.resolver

import java.io.File

object ModuleDetector {

    data class SubmoduleInfo(
        val projectPath: String,   // Gradle path: ":" or ":converter-gson"
        val coordinates: String,   // Maven coords: "com.squareup.retrofit2:converter-gson"
        val moduleName: String?    // short name: null for root, "converter-gson" for submodule
    )

    fun detect(repoDir: File, repoName: String): String? {
        val group = detectGroup(repoDir) ?: return null
        val artifactId = publishedArtifactId(repoDir) ?: detectArtifactId(repoDir) ?: repoName
        return "$group:$artifactId"
    }

    fun detectAllSubmodules(repoDir: File): List<SubmoduleInfo> {
        val settings = readSettings(repoDir)
        val includes = settings?.let { parseSettingsIncludes(it) }.orEmpty()

        if (includes.isEmpty()) {
            val coords = detect(repoDir, repoDir.name) ?: return emptyList()
            return listOf(SubmoduleInfo(":", coords, null))
        }

        val projectDirs = settings?.let { parseProjectDirs(it) }.orEmpty()
        val modules = includes.mapNotNull { projectPath ->
            val subDir = projectDirs[projectPath]?.let { File(repoDir, it) } ?: projectPathToDir(repoDir, projectPath)
            // Skip sample/demo apps — they're not libraries
            if (projectPath.substringAfterLast(':') == "app" || isApplication(subDir)) return@mapNotNull null
            val group = detectGroup(subDir) ?: detectGroup(repoDir) ?: return@mapNotNull null
            val published = publishedArtifactId(subDir)
            val artifactId = published ?: projectPath.substringAfterLast(':')
            Pair(published != null, SubmoduleInfo(
                projectPath = projectPath,
                coordinates = "$group:$artifactId",
                moduleName = artifactId
            ))
        }
        // Repos that mark published modules (POM_ARTIFACT_ID, vanniktech/Square style): test helpers and samples are not libraries
        return if (modules.any { it.first }) modules.filter { it.first }.map { it.second } else modules.map { it.second }
    }

    /** Artifact id a module publishes under, when it says so explicitly. */
    private fun publishedArtifactId(dir: File): String? {
        File(dir, "gradle.properties").takeIf { it.isFile }?.let { props ->
            Regex("""^\s*POM_ARTIFACT_ID\s*=\s*(\S+)""", RegexOption.MULTILINE).find(props.readText())?.let { return it.groupValues[1] }
        }
        val script = listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.firstOrNull { it.isFile }
            ?.readText()?.let { ScriptParser.stripComments(it) } ?: return null
        return listOf(
            Regex("""archivesBaseName\s*=\s*["']([^"']+)["']"""),
            Regex("""archivesName\s*(?:\.set\s*\(|=)\s*["']([^"']+)["']"""),
            Regex("""artifactId\s*=\s*["']([^"']+)["']""")
        ).firstNotNullOfOrNull { it.find(script)?.groupValues?.get(1) }
    }

    private fun readSettings(repoDir: File): String? =
        (File(repoDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(repoDir, "settings.gradle").takeIf { it.exists() })
            ?.readText()?.let { ScriptParser.stripComments(it) }

    /**
     * `include(":a", ":b")` (also across lines), Groovy `include ':a', ':b'`, and paths
     * without a leading colon (`include("a")`). Never matches `includeBuild`.
     */
    private fun parseSettingsIncludes(content: String): List<String> {
        val results = mutableListOf<String>()
        ScriptParser.findCalls(content, setOf("include")).forEach { call ->
            call.args.mapNotNullTo(results) { ScriptParser.stringLiteral(it.expr) }
        }
        Regex("""(?<![\w.])include[ \t]+(['"][^\n;]*)""").findAll(content).forEach { line ->
            ScriptParser.splitArgs(line.groupValues[1]).mapNotNullTo(results) { ScriptParser.stringLiteral(it.expr) }
        }
        return results.map { if (it.startsWith(":")) it else ":$it" }.distinct()
    }

    /** `project(":x").projectDir = file("libs/x")` remappings. */
    private fun parseProjectDirs(content: String): Map<String, String> =
        Regex("""project\(\s*["'](:?[^"']+)["']\s*\)\.projectDir\s*=\s*(?:file|File)\(\s*["']([^"']+)["']""")
            .findAll(content)
            .associate { m -> m.groupValues[1].let { if (it.startsWith(":")) it else ":$it" } to m.groupValues[2] }

    private fun isApplication(dir: File): Boolean =
        listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.filter { it.exists() }.any { file ->
            val text = file.readText()
            text.contains("com.android.application") || text.contains("android.application") ||
                Regex("""(?<![\w.])id\s*\(?\s*["']application["']""").containsMatchIn(text) ||
                Regex("""plugins\s*\{[^}]*(?<![\w."-])application(?![\w"-])""").containsMatchIn(text)
        }

    private fun projectPathToDir(repoDir: File, projectPath: String): File {
        // ":converter-gson" -> "converter-gson"
        // ":retrofit-converters:gson" -> "retrofit-converters/gson"
        val relativePath = projectPath.removePrefix(":").replace(':', '/')
        return File(repoDir, relativePath)
    }

    internal fun detectGroup(repoDir: File): String? {
        // 1. gradle.properties — simple and explicit
        val gradleProps = File(repoDir, "gradle.properties")
        if (gradleProps.exists()) {
            // `group=` or the vanniktech/Square convention `GROUP=`
            val match = Regex("""^\s*(?:group|GROUP)\s*=\s*(\S+)""", RegexOption.MULTILINE)
                .find(gradleProps.readText())
            if (match != null) return match.groupValues[1].trim()
        }

        val buildFiles = listOf(
            File(repoDir, "build.gradle.kts"),
            File(repoDir, "build.gradle"),
            File(repoDir, "library/build.gradle.kts"),
            File(repoDir, "library/build.gradle"),
            File(repoDir, "lib/build.gradle.kts"),
            File(repoDir, "lib/build.gradle"),
        )

        for (file in buildFiles) {
            if (!file.exists()) continue
            val content = file.readText()

            // Top-level: group = "..."
            val ktsTop = Regex("""^group\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(content)
            if (ktsTop != null) return ktsTop.groupValues[1]

            val groovyTop = Regex("""^group\s*=?\s*'([^']+)'""", RegexOption.MULTILINE).find(content)
            if (groovyTop != null) return groovyTop.groupValues[1]

            // Inside allprojects { } or subprojects { }
            val blockMatch = Regex(
                """(?:allprojects|subprojects)\s*\{[^}]*?group\s*=\s*["']([^"']+)["']""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).find(content)
            if (blockMatch != null) return blockMatch.groupValues[1]

            // MavenPublication groupId (Android publishing block)
            val mavenGroupId = Regex(
                """groupId\s*=\s*["']([^"']+)["']""",
                RegexOption.MULTILINE
            ).find(content)
            if (mavenGroupId != null) return mavenGroupId.groupValues[1]

            // android { namespace = "..." } — fallback for Android libs with no explicit group
            val namespace = Regex(
                """android\s*\{[^}]*?namespace\s*=\s*["']([^"']+)["']""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).find(content)
            if (namespace != null) return namespace.groupValues[1]
        }
        return null
    }

    fun extractMavenUrls(repoDir: File): List<String> {
        val results = mutableListOf<String>()
        val filesToScan = listOf(
            File(repoDir, "settings.gradle.kts"),
            File(repoDir, "settings.gradle"),
            File(repoDir, "build.gradle.kts"),
            File(repoDir, "build.gradle"),
        )
        val urlPattern = Regex(
            """maven\s*\{[^}]*?url\s*=?\s*(?:uri\s*\(\s*)?["']([^"']+)["']""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        for (file in filesToScan) {
            if (!file.exists()) continue
            urlPattern.findAll(file.readText()).forEach { results.add(it.groupValues[1]) }
        }
        return results.distinct().filter { it.startsWith("http") }
    }

    private fun detectArtifactId(repoDir: File): String? {
        val settingsFiles = listOf(
            File(repoDir, "settings.gradle.kts"),
            File(repoDir, "settings.gradle"),
        )
        for (file in settingsFiles) {
            if (!file.exists()) continue
            val content = file.readText()
            val match = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""").find(content)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}
