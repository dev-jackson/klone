package dev.klone.fixtures

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * Emulates a company's internal Maven repository (Nexus/Artifactory) on localhost with
 * Basic auth. Requests without valid credentials get 401, like the real thing.
 */
class PrivateMavenServer(private val root: File, private val user: String, private val password: String) : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = mutableListOf<String>()

    val url: String get() = "http://127.0.0.1:${server.address.port}/maven/"

    init {
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        server.createContext("/maven/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/maven/")
            synchronized(requests) { requests += "${exchange.requestMethod} $path" }
            val file = File(root, path)
            when {
                exchange.requestHeaders.getFirst("Authorization") != expected -> {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"internal\"")
                    exchange.sendResponseHeaders(401, -1)
                }
                !file.isFile -> exchange.sendResponseHeaders(404, -1)
                exchange.requestMethod == "HEAD" -> {
                    exchange.responseHeaders.add("Content-Length", file.length().toString())
                    exchange.sendResponseHeaders(200, -1)
                }
                else -> {
                    exchange.sendResponseHeaders(200, file.length())
                    exchange.responseBody.use { it.write(file.readBytes()) }
                }
            }
            exchange.close()
        }
        server.start()
    }

    /** Publishes `group:artifact:version` containing class `<group>.<className>` whose `value()` returns [value]. */
    fun publish(group: String, artifact: String, version: String, className: String, value: String) {
        val dir = File(root, "${group.replace('.', '/')}/$artifact/$version").also { it.mkdirs() }
        File(dir, "$artifact-$version.jar").writeBytes(compileJar(group, className, value))
        File(dir, "$artifact-$version.pom").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
            </project>
        """.trimIndent())
    }

    private fun compileJar(pkg: String, className: String, value: String): ByteArray {
        val work = File(root, ".src-${System.nanoTime()}")
        val src = File(work, "src/${pkg.replace('.', '/')}/$className.java").apply {
            parentFile.mkdirs()
            writeText("package $pkg;\npublic class $className { public static String value() { return \"$value\"; } }\n")
        }
        val out = File(work, "classes").apply { mkdirs() }
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("Functional tests need a JDK (javac)")
        check(compiler.run(null, null, null, "--release", "11", "-d", out.absolutePath, src.absolutePath) == 0)
        val bytes = ByteArrayOutputStream()
        JarOutputStream(bytes).use { jar ->
            out.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(out).invariantSeparatorsPath))
                jar.write(f.readBytes())
                jar.closeEntry()
            }
        }
        work.deleteRecursively()
        return bytes.toByteArray()
    }

    override fun close() = server.stop(0)
}
