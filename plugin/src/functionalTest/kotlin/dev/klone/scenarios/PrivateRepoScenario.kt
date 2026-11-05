package dev.klone.scenarios

import dev.klone.fixtures.PrivateMavenServer
import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PrivateRepoScenario : Scenario() {

    @Test
    fun `cloned library resolves its deps from the host's private authenticated repo`() {
        PrivateMavenServer(File(tempDir, "nexus"), "ci-user", "s3cr\$t\"pw").use { nexus ->
            nexus.publish("com.internal", "secret", "1.0", "Secret", "from-nexus")

            val lib = remote("sdk", Projects.javaLibrary(
                "sdk", "sdk-1.0",
                dependencies = """    api("com.internal:secret:1.0")"""
            ) + mapOf(
                "src/main/java/com/example/Bridge.java" to
                    "package com.example;\npublic class Bridge { public static String value() { return com.internal.Secret.value(); } }\n"
            )).tag("1.0")

            val host = host(files = Projects.host(
                dependencies = """gitImplementation("${lib.url}", from = "1.0")""",
                prints = listOf("Bridge.value()"),
                imports = listOf("com.example.Bridge"),
                settingsExtra = """
                    dependencyResolutionManagement {
                        repositories {
                            maven {
                                url = uri("${nexus.url}")
                                isAllowInsecureProtocol = true
                                credentials {
                                    username = "ci-user"
                                    password = "s3cr\${'$'}t\"pw"
                                }
                            }
                            // header-credential repos must not crash propagation
                            maven {
                                url = uri("http://127.0.0.1:1/unused/")
                                isAllowInsecureProtocol = true
                                credentials(HttpHeaderCredentials::class) { name = "X-Token"; value = "t" }
                                authentication { create<HttpHeaderAuthentication>("header") }
                            }
                        }
                    }
                """.trimIndent()
            ))

            assertEquals(listOf("from-nexus"), host.runApp())
            assertTrue(nexus.requests.any { it.endsWith("secret-1.0.jar") })
        }
    }
}
