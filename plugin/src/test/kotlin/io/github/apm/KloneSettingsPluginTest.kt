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
}
