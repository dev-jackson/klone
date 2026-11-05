package dev.klone.scenarios

import dev.klone.fixtures.PrivateMavenServer
import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** How Klone fits into real builds: Groovy, settings DSL, configurations, publishing, credentials, plugin versions. */
class IntegrationScenario : Scenario() {

    @Test
    fun `Groovy host consumes git sources through the version catalog`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "groovy-ok")).tag("1.0")
        val host = host(files = mapOf(
            "settings.gradle" to "plugins { id 'io.github.dev-jackson.klone' }\nrootProject.name = 'host'\ninclude ':app'\n",
            "app/build.gradle" to "plugins { id 'application' }\napplication { mainClass = 'app.Main' }\ndependencies { implementation libs.greeter }\n",
            "app/src/main/java/app/Main.java" to "package app;\npublic class Main { public static void main(String[] a) { System.out.println(\"KLONE> \" + com.example.Greeter.version()); } }\n",
            "gradle/libs.versions.toml" to """
                [libraries]
                greeter = { module = "com.example:greeter", version = "1.0" }
                [metadata]
                klone.greeter = "${lib.url}"
            """.trimIndent()
        ))
        assertEquals(listOf("groovy-ok"), host.runApp())
    }

    @Test
    fun `settings DSL with SPM builders and explicit modules`() {
        val kit = remote("kit", Projects.multiModuleLibrary("kit", "2", listOf("core", "ui"))).tag("2.3.0")
        val host = host(files = Projects.host(
            dependencies = """implementation("com.example:core")""",
            settingsExtra = """
                klone {
                    git("${kit.url}") {
                        from("2.0.0")
                        modules(listOf("core"))
                    }
                }
            """.trimIndent(),
            prints = listOf("com.example.Core.version()")
        ))
        assertEquals(listOf("core-2"), host.runApp())
    }

    @Test
    fun `configuration parameter exposes the dependency as api`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "via-api")).tag("1.0")
        val host = host(files = Projects.host(
            dependencies = """implementation(project(":lib"))""",
            prints = listOf("com.example.Greeter.version()"),
            settingsExtra = """include(":lib")"""
        ) + mapOf(
            "lib/build.gradle.kts" to """
                plugins { `java-library`; id("io.github.dev-jackson.klone.project") }
                dependencies { gitImplementation("${lib.url}", exact = "1.0", configuration = "api") }
            """.trimIndent()
        ))
        assertEquals(listOf("via-api"), host.runApp())
    }

    @Test
    fun `missing settings plugin gives an actionable error`() {
        val host = host(files = Projects.host(dependencies = """gitImplementation("https://example.com/x", exact = "1")""", prints = emptyList()))
        host.write(mapOf("settings.gradle.kts" to "rootProject.name = \"host\"\ninclude(\":app\")\n"))
        val output = host.buildAndFail(":app:dependencies").output
        assertTrue(output.contains("was not resolved in the settings phase") && output.contains("io.github.dev-jackson.klone"), output)
    }

    @Test
    fun `published POM of a host library carries the catalog's real coordinates`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "x")).tag("1.4.0")
        val repo = File(tempDir, "published")
        val host = host(files = Projects.host(dependencies = "", prints = emptyList(), settingsExtra = """include(":lib")""",
            catalog = """
                [libraries]
                greeter = { module = "com.example:greeter", version = "1.4.0" }
                [metadata]
                klone.greeter = "${lib.url}"
            """
        ) + mapOf(
            "lib/build.gradle.kts" to """
                plugins { `java-library`; `maven-publish` }
                group = "com.host"; version = "0.1"
                dependencies { api(libs.greeter) }
                publishing {
                    publications { create<MavenPublication>("lib") { from(components["java"]) } }
                    repositories { maven { url = uri("${repo.toURI()}") } }
                }
            """.trimIndent()
        ))
        host.build(":lib:publish")
        val pom = File(repo, "com/host/lib/0.1/lib-0.1.pom").readText()
        assertTrue(pom.contains("<artifactId>greeter</artifactId>") && pom.contains("<version>1.4.0</version>"), pom)
    }

    @Test
    fun `credentials from Gradle properties are passed by reference, never written`() {
        PrivateMavenServer(File(tempDir, "nexus"), "ci-user", "top-secret").use { nexus ->
            nexus.publish("com.internal", "secret", "1.0", "Secret", "by-reference")
            val lib = remote("sdk", Projects.javaLibrary("sdk", "s", dependencies = """    api("com.internal:secret:1.0")""") + mapOf(
                "src/main/java/com/example/Bridge.java" to "package com.example;\npublic class Bridge { public static String value() { return com.internal.Secret.value(); } }\n"
            )).tag("1.0")
            val host = host(files = Projects.host(
                dependencies = """gitImplementation("${lib.url}", exact = "1.0")""",
                prints = listOf("com.example.Bridge.value()"),
                settingsExtra = """
                    dependencyResolutionManagement {
                        repositories {
                            maven {
                                name = "internal"
                                url = uri("${nexus.url}")
                                isAllowInsecureProtocol = true
                                credentials(PasswordCredentials::class)
                            }
                        }
                    }
                """.trimIndent()
            ))
            assertEquals(listOf("by-reference"), host.runApp("-PinternalUsername=ci-user", "-PinternalPassword=top-secret"))
            val injected = cacheDir.walkTopDown().first { it.name == "settings.gradle.kts" && it.readText().contains("klone-repos") }.readText()
            assertTrue(injected.contains("credentials(PasswordCredentials::class)"), injected)
            assertFalse(injected.contains("top-secret"), injected)
        }
    }

    @Test
    fun `host secrets never reach the cache, referenced repo credentials and opt-ins do`() {
        PrivateMavenServer(File(tempDir, "nexus"), "ci-user", "root-props-secret").use { nexus ->
            nexus.publish("com.internal", "secret", "1.0", "Secret", "root-props")
            val lib = remote("sdk", Projects.javaLibrary("sdk", "s", dependencies = """    api("com.internal:secret:1.0")""") + mapOf(
                "src/main/java/com/example/Bridge.java" to "package com.example;\npublic class Bridge { public static String value() { return com.internal.Secret.value(); } }\n"
            )).tag("1.0")
            val host = host(files = Projects.host(
                dependencies = """gitImplementation("${lib.url}", exact = "1.0")""",
                prints = listOf("com.example.Bridge.value()"),
                settingsExtra = """
                    dependencyResolutionManagement {
                        repositories {
                            maven {
                                name = "internal"
                                url = uri("${nexus.url}")
                                isAllowInsecureProtocol = true
                                credentials(PasswordCredentials::class)
                            }
                        }
                    }
                    klone { propagateProperties("analytics.*") }
                """.trimIndent()
            ) + mapOf(
                // Credentials committed in the host's root gradle.properties (not in ~/.gradle)
                "gradle.properties" to "internalUsername=ci-user\ninternalPassword=root-props-secret\nGITHUB_TOKEN=ghp_leak\nsigningPassword=sign-leak\nfeature.flag=on\n",
                "keystore.properties" to "storePassword=store-leak\n",
                "analytics.properties" to "analytics.apiKey=opted-in\n"
            ))

            assertEquals(listOf("root-props"), host.runApp())
            val clonedProps = cacheDir.walkTopDown().first { it.name == "gradle.properties" && it.readText().contains("klone-injected") }.readText()
            assertTrue(clonedProps.contains("feature.flag=on"), clonedProps)
            assertTrue(clonedProps.contains("internalPassword=root-props-secret"), "referenced credentials are needed by the clone")
            assertTrue(clonedProps.contains("analytics.apiKey=opted-in"), "explicit opt-in")
            listOf("ghp_leak", "sign-leak", "store-leak").forEach { assertFalse(clonedProps.contains(it), "$it leaked: $clonedProps") }
        }
    }

    @Test
    fun `warns when a library uses another AGP or Kotlin line than the host`() {
        val lib = remote("greeter", Projects.javaLibrary("greeter", "1") + mapOf(
            "gradle/libs.versions.toml" to "[versions]\nagp = \"8.2.0\"\nkotlin = \"1.9.22\"\n"
        )).tag("1")
        val host = greeterHost("""gitImplementation("${lib.url}", exact = "1")""", catalog = """
            [versions]
            agp = "8.7.3"
            kotlin = "1.9.25"
        """)
        val output = host.build("help").output
        assertTrue(output.contains("declares Android Gradle Plugin 8.2.0 (host uses 8.7.3)"), output)
        assertFalse(output.contains("Kotlin 1.9.22"), "same major.minor must not warn")
    }
}
