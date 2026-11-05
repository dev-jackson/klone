package dev.klone.scenarios

import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Real Android build: an Android library (AGP) from git consumed by an Android app.
 * Needs an Android SDK (ANDROID_HOME or ~/Library/Android/sdk) with platform 35 and access to
 * Google's Maven repo; otherwise the test is reported as skipped, never as passed.
 */
class AndroidScenario : Scenario() {

    private lateinit var sdk: File

    private val agp = "8.7.3"

    @BeforeEach
    fun requireAndroidEnvironment() {
        // AGP 8.7 supports Gradle 8.9 up to (not including) 9
        System.getProperty("klone.test.gradleVersion")?.let { v ->
            val (major, minor) = v.split('.').map { it.toInt() }.let { it[0] to it.getOrElse(1) { 0 } }
            assumeTrue(major == 8 && minor >= 9, "AGP $agp does not support Gradle $v")
        }
        val candidates = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
            "${System.getProperty("user.home")}/Library/Android/sdk", "${System.getProperty("user.home")}/Android/Sdk")
        sdk = candidates.map { File(it) }.firstOrNull { File(it, "platforms/android-35").isDirectory } ?: File("")
        assumeTrue(sdk.path.isNotEmpty(), "Android SDK with platform 35 not found")
        assumeTrue(googleMavenReachable(), "dl.google.com not reachable")
    }

    private fun googleMavenReachable() = runCatching {
        (URI("https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/$agp/gradle-$agp.pom").toURL()
            .openConnection() as HttpURLConnection).run { connectTimeout = 5000; requestMethod = "HEAD"; responseCode == 200 }
    }.getOrDefault(false)

    private fun androidLibrary(version: String, agpVersion: String = agp) = mapOf(
        "settings.gradle.kts" to """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "android-greeter"
            include(":greeter")
            include(":sample")
        """.trimIndent(),
        "build.gradle.kts" to """plugins { id("com.android.library") version "$agpVersion" apply false; id("com.android.application") version "$agpVersion" apply false }""",
        "gradle.properties" to "android.useAndroidX=true\ngroup=com.example.android\n",
        "greeter/build.gradle.kts" to """
            plugins { id("com.android.library") }
            android { namespace = "com.example.android.greeter"; compileSdk = 35; defaultConfig { minSdk = 24 } }
        """.trimIndent(),
        "greeter/src/main/java/com/example/android/greeter/Greeter.java" to
            "package com.example.android.greeter;\npublic class Greeter { public static String version() { return \"$version\"; } }\n",
        "sample/build.gradle.kts" to """
            plugins { id("com.android.application") }
            android { namespace = "com.example.android.sample"; compileSdk = 35; defaultConfig { minSdk = 24 } }
        """.trimIndent()
    )

    private fun androidHost(dependency: String) = mapOf(
        "settings.gradle.kts" to """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            plugins { id("io.github.dev-jackson.klone") }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "android-host"
            include(":app")
        """.trimIndent(),
        "build.gradle.kts" to """plugins { id("com.android.application") version "$agp" apply false }""",
        "gradle.properties" to "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n",
        "local.properties" to "sdk.dir=${sdk.absolutePath.replace("\\", "\\\\")}\n",
        "app/build.gradle.kts" to """
            plugins { id("com.android.application"); id("io.github.dev-jackson.klone.project") }
            android { namespace = "com.example.host"; compileSdk = 35; defaultConfig { minSdk = 24 } }
            dependencies { $dependency }
        """.trimIndent(),
        "app/src/main/java/com/example/host/Uses.java" to
            "package com.example.host;\npublic class Uses { public static String v() { return com.example.android.greeter.Greeter.version(); } }\n",
        "app/src/main/AndroidManifest.xml" to "<manifest/>\n"
    )

    @Test
    fun `android app builds an android library from git`() {
        val lib = remote("android-greeter", androidLibrary("android-1.0")).tag("1.0.0")
        val host = host(files = androidHost("""gitImplementation("${lib.url}", from = "1.0.0", module = "greeter")"""))

        val result = host.build(":app:assembleDebug")
        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
        assertTrue(!result.output.contains("declares Android Gradle Plugin"), "same AGP must not warn")
        assertTrue(File(host.projectDir, "app/build/outputs/apk/debug/app-debug.apk").isFile)
        // sdk.dir was propagated to the cloned build
        assertTrue(cacheDir.walkTopDown().any { it.name == "local.properties" && it.readText().contains("sdk.dir") })
    }
}
