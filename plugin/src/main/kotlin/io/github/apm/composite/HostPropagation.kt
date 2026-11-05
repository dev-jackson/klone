package dev.klone.composite

import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import java.io.File
import java.util.Properties

/**
 * Copies host configuration into a cloned build so it builds like part of the host:
 * sdk.dir, gradle.properties entries and private Maven repositories.
 *
 * Injected content lives between sentinel markers and is replaced on every run;
 * files are only written when their content changes.
 */
internal object HostPropagation {

    private val logger = Logging.getLogger(HostPropagation::class.java)

    private const val PROPS_START = "# <klone-injected>"
    private const val PROPS_END = "# </klone-injected>"
    private const val REPOS_START = "// <klone-repos>"
    private const val REPOS_END = "// </klone-repos>"

    private val PUBLIC_REPOS = listOf(
        "https://dl.google.com/dl/android/maven2",
        "https://repo1.maven.org/maven2",
        "https://repo.maven.apache.org/maven2",
        "https://jcenter.bintray.com",
        "https://plugins.gradle.org/m2",
    )

    /** Keys that look like secrets are never copied unless explicitly allowed. */
    private val SECRET_KEY = Regex("""(?i)(password|passwd|pwd|secret|token|credential|api[._-]?key|private[._-]?key|signing|keystore|auth)""")

    /**
     * @param allowedProperties glob patterns (`artifactory*`) from `klone { propagateProperties(...) }`:
     *   copied even if they look like secrets, and also read from other `*.properties` files in the root.
     */
    fun propagate(localDir: File, settings: Settings, allowedProperties: List<String> = emptyList()) {
        injectSdkDir(localDir, settings.rootDir)
        val referenced = privateRepos(settings).filter { usesPropertyCredentials(it, settings) }
            .flatMap { listOf("${it.name}Username", "${it.name}Password") }
        injectGradleProperties(localDir, settings.rootDir, allowedProperties + referenced)
        injectHostRepositories(localDir, settings)
    }

    fun injectSdkDir(localDir: File, hostRootDir: File) {
        val clonedProps = File(localDir, "local.properties")
        if (clonedProps.exists() && clonedProps.readText().contains("sdk.dir")) return

        val sdkLine = readSdkDirFromHost(hostRootDir) ?: run {
            logger.info("[Klone] No sdk.dir found in host local.properties — skipping injection for ${localDir.name}")
            return
        }

        if (clonedProps.exists()) {
            clonedProps.appendText("\n$sdkLine\n")
        } else {
            clonedProps.writeText("# Propagated by Klone from host project\n$sdkLine\n")
        }
        logger.lifecycle("[Klone] Propagated sdk.dir into ${localDir.name}/local.properties")
    }

    fun readSdkDirFromHost(hostRootDir: File): String? {
        val hostProps = File(hostRootDir, "local.properties")
        if (!hostProps.exists()) return null
        return Regex("""^sdk\.dir=.+$""", RegexOption.MULTILINE)
            .find(hostProps.readText())
            ?.value
    }

    fun injectGradleProperties(localDir: File, hostRootDir: File, allowed: List<String> = emptyList()) {
        val allowedRegexes = allowed.map { glob -> Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }) }
        fun isAllowed(key: String) = allowedRegexes.any { it.matches(key) }

        // gradle.properties carries the flags a cloned Android build needs (android.useAndroidX, ...).
        // Other *.properties files (credentials, keystore, local.properties) only contribute allowed keys.
        val merged = Properties()
        File(hostRootDir, "gradle.properties").takeIf { it.isFile }?.inputStream()?.use { merged.load(it) }
        if (allowedRegexes.isNotEmpty()) {
            hostRootDir.listFiles { f -> f.isFile && f.name.endsWith(".properties") && f.name !in setOf("gradle.properties", "local.properties") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    Properties().apply { f.inputStream().use { load(it) } }.let { extra ->
                        extra.stringPropertyNames().filter { isAllowed(it) }.forEach { merged[it] = extra.getProperty(it) }
                    }
                }
        }
        val skipped = merged.stringPropertyNames().filter { SECRET_KEY.containsMatchIn(it) && !isAllowed(it) }.sorted()
        skipped.forEach { merged.remove(it) }
        if (skipped.isNotEmpty()) {
            logger.info("[Klone] Not copying secret-looking properties into ${localDir.name}: $skipped (allow with klone { propagateProperties(\"...\") })")
        }

        val clonedPropsFile = File(localDir, "gradle.properties")
        val existingContent = if (clonedPropsFile.exists()) clonedPropsFile.readText() else ""

        // Original content = everything outside the sentinel block (never modified)
        val originalContent = removeBlock(existingContent, PROPS_START, PROPS_END)
        val originalKeys = Properties().also { p ->
            if (originalContent.isNotBlank()) p.load(originalContent.reader())
        }.stringPropertyNames()

        val toInject = merged.stringPropertyNames().filter { it !in originalKeys }.sorted()
        if (toInject.isEmpty()) {
            // Clean up stale sentinel block if nothing to inject anymore
            if (clonedPropsFile.exists()) writeIfChanged(clonedPropsFile, originalContent)
            return
        }

        val lines = toInject.joinToString("\n") { key -> "$key=${escapeProperty(merged.getProperty(key))}" }
        val block = "$PROPS_START\n$lines\n$PROPS_END"
        if (writeIfChanged(clonedPropsFile, upsertBlock(existingContent, PROPS_START, PROPS_END, block))) {
            logger.lifecycle("[Klone] Propagated ${toInject.size} gradle.properties entr${if (toInject.size == 1) "y" else "ies"} into ${localDir.name}")
        }
    }

    fun injectHostRepositories(localDir: File, settings: Settings) {
        val clonedSettingsFile = File(localDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(localDir, "settings.gradle").takeIf { it.exists() }
            ?: return
        val kotlinDsl = clonedSettingsFile.name.endsWith(".kts")

        val repos = privateRepos(settings)

        val content = clonedSettingsFile.readText()
        if (repos.isEmpty()) {
            writeIfChanged(clonedSettingsFile, removeBlock(content, REPOS_START, REPOS_END))
            return
        }

        val repoBlocks = repos.joinToString("\n") { repo -> mavenBlock(repo, kotlinDsl, settings) }
        val block = buildString {
            appendLine(REPOS_START)
            appendLine("dependencyResolutionManagement {")
            appendLine("    repositories {")
            appendLine(repoBlocks)
            appendLine("    }")
            appendLine("}")
            append(REPOS_END)
        }
        if (writeIfChanged(clonedSettingsFile, upsertBlock(content, REPOS_START, REPOS_END, block))) {
            logger.lifecycle("[Klone] Propagated ${repos.size} private Maven repo(s) into ${localDir.name}/${clonedSettingsFile.name}")
        }
    }

    fun writeSettingsSentinel(file: File, sentinelBlock: String) {
        writeIfChanged(file, upsertBlock(file.readText(), REPOS_START, REPOS_END, sentinelBlock))
    }

    private fun mavenBlock(repo: MavenArtifactRepository, kotlinDsl: Boolean, settings: Settings): String {
        // Credentials Gradle reads from properties (<name>Username / <name>Password: ~/.gradle/gradle.properties,
        // ORG_GRADLE_PROJECT_* env, -P) are passed by reference — the secret is never written to disk.
        val byReference = usesPropertyCredentials(repo, settings)
        val creds = if (byReference) null else runCatching { repo.getCredentials(PasswordCredentials::class.java) }.getOrElse {
            // getCredentials() throws when the repo uses another type (e.g. HttpHeaderCredentials)
            logger.warn("[Klone] Repo ${repo.url} uses non-password credentials — propagated without credentials")
            null
        }
        if (creds?.password != null) {
            logger.warn(
                "[Klone] Credentials of ${repo.url} are written in plain text to the cached settings file. " +
                    "Use credentials(PasswordCredentials::class) with ${repo.name}Username/${repo.name}Password properties to avoid it."
            )
        }
        val insecure = if (kotlinDsl) "isAllowInsecureProtocol = true" else "allowInsecureProtocol = true"
        val credentialsByType = if (kotlinDsl) "credentials(PasswordCredentials::class)" else "credentials(PasswordCredentials)"
        return buildString {
            appendLine("        maven {")
            if (byReference) appendLine("            name = \"${escapeString(repo.name)}\"")
            appendLine("            url = uri(\"${escapeString(repo.url.toString())}\")")
            if (repo.isAllowInsecureProtocol) appendLine("            $insecure")
            if (byReference) {
                appendLine("            $credentialsByType")
            } else if (creds?.username != null || creds?.password != null) {
                appendLine("            credentials {")
                creds.username?.let { appendLine("                username = \"${escapeString(it)}\"") }
                creds.password?.let { appendLine("                password = \"${escapeString(it)}\"") }
                appendLine("            }")
            }
            append("        }")
        }
    }

    private fun privateRepos(settings: Settings): List<MavenArtifactRepository> =
        settings.dependencyResolutionManagement.repositories
            .filterIsInstance<MavenArtifactRepository>()
            .filter { repo -> PUBLIC_REPOS.none { repo.url.toString().startsWith(it) } }

    private fun usesPropertyCredentials(repo: MavenArtifactRepository, settings: Settings): Boolean =
        repo.name.isNotBlank() &&
            settings.providers.gradleProperty("${repo.name}Username").isPresent &&
            settings.providers.gradleProperty("${repo.name}Password").isPresent

    // ── sentinel blocks ─────────────────────────────────────────────────────

    /** Replaces the block between [start] and [end], or appends [block] after a blank line. */
    fun upsertBlock(content: String, start: String, end: String, block: String): String {
        val s = content.indexOf(start)
        val e = if (s >= 0) content.indexOf(end, s) else -1
        if (s >= 0 && e >= 0) return content.substring(0, s) + block + content.substring(e + end.length)
        val base = content.trimEnd('\n')
        return if (base.isEmpty()) "$block\n" else "$base\n\n$block\n"
    }

    fun removeBlock(content: String, start: String, end: String): String {
        val s = content.indexOf(start)
        val e = if (s >= 0) content.indexOf(end, s) else -1
        if (s == -1 || e == -1) return content
        val before = content.substring(0, s).trimEnd('\n')
        val after = content.substring(e + end.length).trimStart('\n')
        return if (before.isEmpty()) after else "$before\n$after"
    }

    /** Returns true when the file changed. Unchanged files keep their timestamp (no config cache misses). */
    private fun writeIfChanged(file: File, content: String): Boolean {
        if (file.exists() && file.readText() == content) return false
        file.writeText(content)
        return true
    }

    private fun escapeString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    private fun escapeProperty(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n")
}
