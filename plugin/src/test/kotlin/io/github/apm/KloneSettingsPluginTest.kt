package dev.klone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KloneSettingsPluginTest {

    private val plugin = KloneSettingsPlugin()

    @TempDir
    lateinit var tempDir: File

    // ── readSdkDirFromHost ──────────────────────────────────────────────────

    @Test
    fun `readSdkDirFromHost returns null when host local properties missing`() {
        assertNull(plugin.readSdkDirFromHost(tempDir))
    }

    @Test
    fun `readSdkDirFromHost returns sdk dir line from host`() {
        File(tempDir, "local.properties").writeText("sdk.dir=/Users/alice/Library/Android/sdk\n")
        assertEquals("sdk.dir=/Users/alice/Library/Android/sdk", plugin.readSdkDirFromHost(tempDir))
    }

    // ── injectSdkDir ───────────────────────────────────────────────────────

    @Test
    fun `injectSdkDir creates local properties when missing`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "local.properties").writeText("sdk.dir=/opt/android-sdk\n")

        plugin.injectSdkDir(clonedDir, hostDir)

        val result = File(clonedDir, "local.properties").readText()
        assertTrue(result.contains("sdk.dir=/opt/android-sdk"), "Expected sdk.dir in created file")
    }

    @Test
    fun `injectSdkDir appends sdk dir to existing local properties without sdk dir`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "local.properties").writeText("sdk.dir=/opt/android-sdk\n")
        File(clonedDir, "local.properties").writeText("# existing content\n")

        plugin.injectSdkDir(clonedDir, hostDir)

        val result = File(clonedDir, "local.properties").readText()
        assertTrue(result.contains("# existing content"), "Existing content should be preserved")
        assertTrue(result.contains("sdk.dir=/opt/android-sdk"), "sdk.dir should be appended")
    }

    @Test
    fun `injectSdkDir does not overwrite existing sdk dir`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "local.properties").writeText("sdk.dir=/host/android-sdk\n")
        File(clonedDir, "local.properties").writeText("sdk.dir=/cloned/android-sdk\n")

        plugin.injectSdkDir(clonedDir, hostDir)

        val result = File(clonedDir, "local.properties").readText()
        assertTrue(result.contains("sdk.dir=/cloned/android-sdk"), "Original sdk.dir should be untouched")
        assertTrue(!result.contains("/host/android-sdk"), "Host sdk.dir should not be injected")
    }

    @Test
    fun `injectSdkDir does nothing when host has no local properties`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        // No local.properties in hostDir

        plugin.injectSdkDir(clonedDir, hostDir)

        assertTrue(!File(clonedDir, "local.properties").exists(), "No file should be created when host has no local.properties")
    }

    // ── injectGradleProperties ─────────────────────────────────────────────

    @Test
    fun `injectGradleProperties creates gradle properties from host properties files when missing`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "gradle.properties").writeText("myKey=myValue\norg.gradle.jvmargs=-Xmx2g\n")

        plugin.injectGradleProperties(clonedDir, hostDir)

        val result = File(clonedDir, "gradle.properties").readText()
        assertTrue(result.contains("myKey=myValue"), "Expected myKey propagated")
        assertTrue(result.contains("org.gradle.jvmargs=-Xmx2g"), "Expected jvmargs propagated")
    }

    @Test
    fun `injectGradleProperties merges multiple properties files from host root`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "gradle.properties").writeText("keyA=valueA\n")
        File(hostDir, "credentials.properties").writeText("keyB=valueB\n")

        plugin.injectGradleProperties(clonedDir, hostDir)

        val result = File(clonedDir, "gradle.properties").readText()
        assertTrue(result.contains("keyA=valueA"), "Expected keyA from gradle.properties")
        assertTrue(result.contains("keyB=valueB"), "Expected keyB from credentials.properties")
    }

    @Test
    fun `injectGradleProperties does not overwrite existing keys`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "gradle.properties").writeText("sharedKey=hostValue\nnewKey=newValue\n")
        File(clonedDir, "gradle.properties").writeText("sharedKey=clonedValue\n")

        plugin.injectGradleProperties(clonedDir, hostDir)

        val result = File(clonedDir, "gradle.properties").readText()
        assertTrue(result.contains("sharedKey=clonedValue"), "Existing key must not be overwritten")
        assertTrue(!result.contains("sharedKey=hostValue"), "Host value must not replace cloned value")
        assertTrue(result.contains("newKey=newValue"), "New key from host should be injected")
    }

    @Test
    fun `injectGradleProperties does nothing when host has no properties files`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        // No *.properties files in hostDir

        plugin.injectGradleProperties(clonedDir, hostDir)

        assertTrue(!File(clonedDir, "gradle.properties").exists(), "No file should be created when host has no properties")
    }

    @Test
    fun `injectGradleProperties appends to existing gradle properties`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "gradle.properties").writeText("injectedKey=injectedValue\n")
        File(clonedDir, "gradle.properties").writeText("# existing content\nexistingKey=existingValue\n")

        plugin.injectGradleProperties(clonedDir, hostDir)

        val result = File(clonedDir, "gradle.properties").readText()
        assertTrue(result.contains("# existing content"), "Existing content must be preserved")
        assertTrue(result.contains("existingKey=existingValue"), "Existing key must be preserved")
        assertTrue(result.contains("injectedKey=injectedValue"), "New key must be appended")
    }

    @Test
    fun `injectGradleProperties replaces sentinel block on second run with updated values`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        // First run
        File(hostDir, "gradle.properties").writeText("token=old-token\n")
        plugin.injectGradleProperties(clonedDir, hostDir)

        val afterFirst = File(clonedDir, "gradle.properties").readText()
        assertTrue(afterFirst.contains("token=old-token"), "Token should be injected on first run")

        // Second run — token rotated
        File(hostDir, "gradle.properties").writeText("token=new-token\n")
        plugin.injectGradleProperties(clonedDir, hostDir)

        val afterSecond = File(clonedDir, "gradle.properties").readText()
        assertTrue(afterSecond.contains("token=new-token"), "Token should be updated on second run")
        assertTrue(!afterSecond.contains("token=old-token"), "Old token must not remain after rotation")
    }

    // ── injectHostRepositories ────────────────────────────────────────────

    @Test
    fun `injectHostRepositories does nothing when host has no private repos`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "settings.gradle.kts").writeText("""
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
        """.trimIndent())
        val clonedSettings = File(clonedDir, "settings.gradle.kts")
        clonedSettings.writeText("rootProject.name = \"mylib\"\n")

        plugin.injectHostRepositories(clonedDir, hostDir)

        val result = clonedSettings.readText()
        assertTrue(!result.contains("klone-repos"), "No sentinel block should be created when no private repos exist")
    }

    @Test
    fun `injectHostRepositories injects private maven block into cloned settings`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "settings.gradle.kts").writeText("""
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                    maven { url = uri("https://maven.pkg.github.com/org/repo") }
                }
            }
        """.trimIndent())
        File(clonedDir, "settings.gradle.kts").writeText("rootProject.name = \"mylib\"\n")

        plugin.injectHostRepositories(clonedDir, hostDir)

        val result = File(clonedDir, "settings.gradle.kts").readText()
        assertTrue(result.contains("// <klone-repos>"), "Sentinel start should be present")
        assertTrue(result.contains("// </klone-repos>"), "Sentinel end should be present")
        assertTrue(result.contains("https://maven.pkg.github.com/org/repo"), "Private repo URL should be injected")
        assertTrue(result.contains("rootProject.name"), "Original content should be preserved")
    }

    @Test
    fun `injectHostRepositories preserves existing cloned settings content`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "settings.gradle.kts").writeText("""
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("https://private.company.com/maven") }
                }
            }
        """.trimIndent())
        File(clonedDir, "settings.gradle.kts").writeText("""
            rootProject.name = "mylib"
            include(":core", ":ui")
        """.trimIndent())

        plugin.injectHostRepositories(clonedDir, hostDir)

        val result = File(clonedDir, "settings.gradle.kts").readText()
        assertTrue(result.contains("rootProject.name = \"mylib\""), "Root project name must be preserved")
        assertTrue(result.contains("include(\":core\", \":ui\")"), "Includes must be preserved")
        assertTrue(result.contains("https://private.company.com/maven"), "Private repo must be injected")
    }

    @Test
    fun `injectHostRepositories replaces sentinel on second run`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(clonedDir, "settings.gradle.kts").writeText("rootProject.name = \"mylib\"\n")

        // First run
        File(hostDir, "settings.gradle.kts").writeText("""
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("https://old.private.com/maven") }
                }
            }
        """.trimIndent())
        plugin.injectHostRepositories(clonedDir, hostDir)

        // Second run — URL changed
        File(hostDir, "settings.gradle.kts").writeText("""
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("https://new.private.com/maven") }
                }
            }
        """.trimIndent())
        plugin.injectHostRepositories(clonedDir, hostDir)

        val result = File(clonedDir, "settings.gradle.kts").readText()
        assertTrue(result.contains("https://new.private.com/maven"), "New URL should be present")
        assertTrue(!result.contains("https://old.private.com/maven"), "Old URL must be removed")
        assertEquals(1, result.split("// <klone-repos>").size - 1, "Only one sentinel block should exist")
    }

    @Test
    fun `injectHostRepositories handles nested credentials block`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "settings.gradle.kts").writeText("""
            dependencyResolutionManagement {
                repositories {
                    google()
                    maven {
                        url = uri("https://maven.pkg.github.com/org/repo")
                        credentials {
                            username = providers.gradleProperty("gpr.user").get()
                            password = providers.gradleProperty("gpr.token").get()
                        }
                    }
                }
            }
        """.trimIndent())
        File(clonedDir, "settings.gradle.kts").writeText("rootProject.name = \"mylib\"\n")

        plugin.injectHostRepositories(clonedDir, hostDir)

        val result = File(clonedDir, "settings.gradle.kts").readText()
        assertTrue(result.contains("credentials"), "Credentials block should be preserved")
        assertTrue(result.contains("gpr.user"), "Username property reference should be preserved")
        assertTrue(result.contains("gpr.token"), "Password property reference should be preserved")
        assertTrue(result.contains("https://maven.pkg.github.com/org/repo"), "URL should be present")
    }

    // ── extractMavenBlocks ──────────────────────────────────────────────

    @Test
    fun `extractMavenBlocks extracts simple and nested blocks`() {
        val content = """
            repositories {
                google()
                maven { url = uri("https://simple.com/repo") }
                maven {
                    url = uri("https://complex.com/repo")
                    credentials {
                        username = "user"
                        password = "pass"
                    }
                }
            }
        """.trimIndent()

        val blocks = plugin.extractMavenBlocks(content)
        assertEquals(2, blocks.size, "Should find 2 maven blocks")
        assertTrue(blocks[0].contains("https://simple.com/repo"))
        assertTrue(blocks[1].contains("credentials"))
        assertTrue(blocks[1].contains("https://complex.com/repo"))
    }

    @Test
    fun `extractUrlFromMavenBlock extracts url with uri wrapper`() {
        val block = """maven { url = uri("https://example.com/repo") }"""
        assertEquals("https://example.com/repo", plugin.extractUrlFromMavenBlock(block))
    }

    @Test
    fun `extractUrlFromMavenBlock extracts url without uri wrapper`() {
        val block = """maven { url = "https://example.com/repo" }"""
        assertEquals("https://example.com/repo", plugin.extractUrlFromMavenBlock(block))
    }

    // ── injectGradleProperties (continued) ──────────────────────────────

    @Test
    fun `injectGradleProperties preserves original cloned keys across sentinel block replacement`() {
        val hostDir = File(tempDir, "host").also { it.mkdirs() }
        val clonedDir = File(tempDir, "cloned").also { it.mkdirs() }

        File(hostDir, "gradle.properties").writeText("hostKey=value1\n")
        File(clonedDir, "gradle.properties").writeText("clonedOriginal=unchanged\n")

        // First run injects hostKey
        plugin.injectGradleProperties(clonedDir, hostDir)

        // Second run with changed host value
        File(hostDir, "gradle.properties").writeText("hostKey=value2\n")
        plugin.injectGradleProperties(clonedDir, hostDir)

        val result = File(clonedDir, "gradle.properties").readText()
        assertTrue(result.contains("clonedOriginal=unchanged"), "Original cloned key must be preserved")
        assertTrue(result.contains("hostKey=value2"), "Host key should be updated to new value")
        assertTrue(!result.contains("hostKey=value1"), "Old host value must not remain")
    }
}
