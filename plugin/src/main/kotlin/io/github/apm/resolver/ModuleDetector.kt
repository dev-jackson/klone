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
        val artifactId = detectArtifactId(repoDir) ?: repoName
        return "$group:$artifactId"
    }

    fun detectAllSubmodules(repoDir: File): List<SubmoduleInfo> {
        val includes = parseSettingsIncludes(repoDir)

        if (includes.isEmpty()) {
            val coords = detect(repoDir, repoDir.name) ?: return emptyList()
            return listOf(SubmoduleInfo(":", coords, null))
        }

        return includes.mapNotNull { projectPath ->
            val subDir = projectPathToDir(repoDir, projectPath)
            val group = detectGroup(subDir) ?: detectGroup(repoDir) ?: return@mapNotNull null
            val artifactId = projectPath.substringAfterLast(':')
            SubmoduleInfo(
                projectPath = projectPath,
                coordinates = "$group:$artifactId",
                moduleName = artifactId
            )
        }
    }

    private fun parseSettingsIncludes(repoDir: File): List<String> {
        val settingsFile = File(repoDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(repoDir, "settings.gradle").takeIf { it.exists() }
            ?: return emptyList()

        val content = settingsFile.readText()
        val results = mutableListOf<String>()

        // Capture each include(...) / include ... line, then extract all ":path" tokens from it
        Regex("""include\b[^\n;]*""").findAll(content).forEach { includeLine ->
            Regex("""["'](:[^"'\s,)]+)["']""").findAll(includeLine.value).forEach { pathMatch ->
                results.add(pathMatch.groupValues[1])
            }
        }

        return results.distinct()
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
            val match = Regex("""^\s*group\s*=\s*(\S+)""", RegexOption.MULTILINE)
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
