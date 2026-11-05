package dev.klone

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.SemVer
import dev.klone.resolver.LockFileManager
import dev.klone.resolver.PluginVersions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class SemVerAndPoliciesTest {

    @TempDir
    lateinit var tempDir: File

    private val tags = listOf("0.4.0", "0.4.9", "0.5.0", "1.0.0", "v1.2.0", "1.2.5", "1.10.0", "2.0.0", "2.1.0-rc1", "nightly")
        .mapNotNull { t -> SemVer.parse(t)?.let { t to it } }.toMap()

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "^1.0          | 1.10.0",
            "^0.4          | 0.4.9",
            "~1.2.0        | 1.2.5",
            ">=1.0 <1.5    | 1.2.5",
            "1.+           | 1.10.0",
            "1.2.+         | 1.2.5",
            "[1.0,2.0)     | 1.10.0",
            "[1.0,2.0]     | 2.0.0",
            "(,1.0)        | 0.5.0",
            "latest.release| 2.0.0",
            ">=2.1.0-rc1   | 2.1.0-rc1",
        ]
    )
    fun `ranges pick the highest matching tag`(spec: String, expected: String) {
        assertEquals(expected, SemVer.best(spec.trim(), tags))
    }

    @Test
    fun `SPM from and upToNextMinor`() {
        assertEquals(">=1.4.0 <2.0.0", SemVer.upToNextMajor("1.4.0"))
        assertEquals(">=0.4.0 <1.0.0", SemVer.upToNextMajor("0.4.0"))
        assertEquals(">=1.4.2 <1.5.0", SemVer.upToNextMinor("1.4.2"))
        assertEquals("1.10.0", SemVer.best(SemVer.upToNextMajor("1.0.0"), tags))
        assertThrows<IllegalArgumentException> { SemVer.upToNextMajor("main") }
    }

    @Test
    fun `ordering, prerelease and tag patterns`() {
        assertTrue(SemVer.parse("1.10.0")!! > SemVer.parse("1.9.9")!!)
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("1.0.0-rc1")!!)
        assertEquals(SemVer.parse("1.0")!!.compareTo(SemVer.parse("1.0.0")!!), 0)
        assertEquals("2.3.0", SemVer.fromTag("sdk-2.3.0", "sdk-{version}").toString())
        assertNull(SemVer.fromTag("other-2.3.0", "sdk-{version}"))
        assertEquals("sdk-2.3.0", SemVer.toTag("2.3.0", "sdk-{version}"))
        assertTrue(SemVer.isRange("^1") && SemVer.isRange("1.+") && SemVer.isRange("[1,2)") && !SemVer.isRange("1.2.0"))
    }

    @Test
    fun `GitRef from SPM params`() {
        assertEquals(GitRef.Range(">=1.0.0 <2.0.0"), GitRef.fromParams(from = "1.0.0"))
        assertEquals(GitRef.Tag("1.0.0"), GitRef.fromParams(exact = "1.0.0"))
        assertEquals(GitRef.Range("1.+"), GitRef.fromParams(version = "1.+"))
        assertEquals(GitRef.Tag("1.2"), GitRef.fromParams(version = "1.2"))
        assertNull(GitRef.fromParams())
        assertThrows<IllegalArgumentException> { GitRef.fromParams(from = "1.0.0", branch = "main") }
        assertEquals(GitRef.Range("^1"), GitRef.of("range", "^1"))
    }

    @Test
    fun `update request matches repo name or url fragment`() {
        val dep = GitDependency("https://github.com/org/design-system.git")
        assertTrue(KloneSettingsPlugin.UpdateRequest.parse("true").matches(dep))
        assertTrue(KloneSettingsPlugin.UpdateRequest.parse("design-system").matches(dep))
        assertTrue(KloneSettingsPlugin.UpdateRequest.parse("other, org/design").matches(dep))
        assertFalse(KloneSettingsPlugin.UpdateRequest.parse("design").matches(GitDependency("https://github.com/org/core")))
        assertFalse(KloneSettingsPlugin.UpdateRequest.parse(null).any)
        assertFalse(KloneSettingsPlugin.UpdateRequest.parse("false").any)
    }

    @Test
    fun `lock diff reports new, changed and removed dependencies`() {
        LockFileManager(tempDir).write(listOf(
            LockFileManager.LockEntry("u1", "tag", "1", "aaaaaaaa", null),
            LockFileManager.LockEntry("u2", "branch", "main", "bbbbbbbb", null)
        ))
        val diff = LockFileManager(tempDir).diff(listOf(
            LockFileManager.LockEntry("u1", "tag", "2", "cccccccc", null),
            LockFileManager.LockEntry("u3", "tag", "1", "dddddddd", null)
        ))
        assertEquals(listOf("u1: ref changed (tag 1 → tag 2)", "u3: not in klone.resolved", "u2: no longer declared"), diff)
    }

    @Test
    fun `plugin versions from catalog plugins, versions and build scripts`() {
        File(tempDir, "gradle").mkdirs()
        File(tempDir, "gradle/libs.versions.toml").writeText("""
            [versions]
            androidGradle = "8.5.1"
            [plugins]
            android-library = { id = "com.android.library", version.ref = "androidGradle" }
            kotlin-android = { id = "org.jetbrains.kotlin.android", version = "2.0.21" }
        """.trimIndent())
        assertEquals(mapOf(PluginVersions.AGP to "8.5.1", PluginVersions.KOTLIN to "2.0.21"), PluginVersions.detect(tempDir))

        val legacy = File(tempDir, "legacy").also { it.mkdirs() }
        File(legacy, "build.gradle").writeText("""
            buildscript { dependencies {
                classpath 'com.android.tools.build:gradle:7.4.2'
                classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10"
            } }
        """.trimIndent())
        assertEquals(mapOf(PluginVersions.AGP to "7.4.2", PluginVersions.KOTLIN to "1.8.10"), PluginVersions.detect(legacy))

        assertEquals(listOf("Android Gradle Plugin 7.4.2 (host uses 8.5.1)", "Kotlin 1.8.10 (host uses 2.0.21)"),
            PluginVersions.incompatibilities(PluginVersions.detect(tempDir), PluginVersions.detect(legacy)))
        assertEquals(emptyList<String>(), PluginVersions.incompatibilities(mapOf(PluginVersions.AGP to "8.5.0"), mapOf(PluginVersions.AGP to "8.5.2")))
    }
}

class LockMigrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `1x lock entry for from is reused by the 2x range`() {
        File(tempDir, "klone.resolved").writeText("""
            {
              "version": 1,
              "dependencies": [
                { "url": "https://github.com/square/retrofit", "refType": "tag", "refValue": "2.9.0",
                  "resolvedCommit": "abc123", "resolvedModuleCoordinates": "com.squareup.retrofit2:retrofit",
                  "resolvedAt": "2026-01-01T00:00:00Z" }
              ]
            }
        """.trimIndent())
        val lock = LockFileManager(tempDir)
        val migrated = lock.locked("https://github.com/square/retrofit", GitRef.upToNextMajor("2.9.0"))
        assertEquals("abc123", migrated?.resolvedCommit)
        assertEquals("2.9.0", migrated?.resolvedTag)
        // Other ranges never match a legacy tag entry
        assertNull(lock.locked("https://github.com/square/retrofit", GitRef.upToNextMajor("2.10.0")))
        assertNull(lock.locked("https://github.com/square/retrofit", GitRef.Range("^2.9.0")))
    }
}
