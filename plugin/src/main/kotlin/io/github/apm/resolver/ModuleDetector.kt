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
        return Regex("""include\s*\(\s*["']([^"']+)["']\s*\)""")
            .findAll(content)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun projectPathToDir(repoDir: File, projectPath: String): File {
        // ":converter-gson" -> "converter-gson"
        // ":retrofit-converters:gson" -> "retrofit-converters/gson"
        val relativePath = projectPath.removePrefix(":").replace(':', '/')
        return File(repoDir, relativePath)
    }

    internal fun detectGroup(repoDir: File): String? {
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
            val ktsMatch = Regex("""group\s*=\s*"([^"]+)"""").find(content)
            if (ktsMatch != null) return ktsMatch.groupValues[1]
            val groovyMatch = Regex("""group\s*=?\s*'([^']+)'""").find(content)
            if (groovyMatch != null) return groovyMatch.groupValues[1]
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
