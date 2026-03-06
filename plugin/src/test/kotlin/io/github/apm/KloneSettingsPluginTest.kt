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
