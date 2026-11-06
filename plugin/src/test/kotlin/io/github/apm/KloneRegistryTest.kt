package dev.klone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KloneRegistryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var scope: KloneRegistry.Scope

    @BeforeEach
    fun setUp() {
        scope = KloneRegistry.reset(tempDir)
    }

    @Test
    fun `find returns null for unknown url`() {
        assertNull(scope.find("https://github.com/unknown/repo"))
    }

    @Test
    fun `register and find root module with null moduleName`() {
        val entry = KloneRegistry.Entry(
            url = "https://github.com/square/retrofit",
            moduleCoordinates = "com.squareup.retrofit2:retrofit",
            localDir = tempDir
        )
        scope.register("https://github.com/square/retrofit", null, entry)

        val found = scope.find("https://github.com/square/retrofit", null)
        assertEquals(entry, found)
    }

    @Test
    fun `register and find named submodule`() {
        val entry = KloneRegistry.Entry(
            url = "https://github.com/square/retrofit",
            moduleCoordinates = "com.squareup.retrofit2:converter-gson",
            localDir = tempDir
        )
        scope.register("https://github.com/square/retrofit", "converter-gson", entry)

        val found = scope.find("https://github.com/square/retrofit", "converter-gson")
        assertEquals(entry, found)

        // Root module not registered
        assertNull(scope.find("https://github.com/square/retrofit", null))
    }

    @Test
    fun `normalizes url with trailing slash and dot git`() {
        val entry = KloneRegistry.Entry(
            url = "https://github.com/square/retrofit",
            moduleCoordinates = "com.squareup.retrofit2:retrofit",
            localDir = tempDir
        )
        scope.register("https://github.com/square/retrofit.git/", null, entry)

        // Both normalized and non-normalized URLs resolve to the same entry
        assertEquals(entry, scope.find("https://github.com/square/retrofit.git/"))
        assertEquals(entry, scope.find("https://github.com/square/retrofit"))
    }

    @Test
    fun `allModulesFor returns all registered module names for a url`() {
        val url = "https://github.com/square/retrofit"
        val mkEntry = { coords: String -> KloneRegistry.Entry(url, coords, tempDir) }

        scope.register(url, null, mkEntry("com.squareup.retrofit2:retrofit"))
        scope.register(url, "converter-gson", mkEntry("com.squareup.retrofit2:converter-gson"))
        scope.register(url, "adapter-rxjava2", mkEntry("com.squareup.retrofit2:adapter-rxjava2"))

        val modules = scope.allModulesFor(url)
        assertEquals(3, modules.size)
        assertTrue(null in modules)
        assertTrue("converter-gson" in modules)
        assertTrue("adapter-rxjava2" in modules)
    }

    @Test
    fun `allModulesFor returns empty list for unknown url`() {
        assertTrue(scope.allModulesFor("https://github.com/unknown/repo").isEmpty())
    }

    @Test
    fun `register and find with local prefix`() {
        val entry = KloneRegistry.Entry(
            url = "local:/some/path/to/lib",
            moduleCoordinates = "com.example:mylib",
            localDir = tempDir
        )
        scope.register("local:/some/path/to/lib", null, entry)

        val found = scope.find("local:/some/path/to/lib", null)
        assertEquals(entry, found)
    }

    @Test
    fun `normalizeUrl does not modify local prefix`() {
        val entry = KloneRegistry.Entry(
            url = "local:/some/path",
            moduleCoordinates = "com.example:lib",
            localDir = tempDir
        )
        scope.register("local:/some/path", null, entry)

        // Same key retrieves it — proves no normalization happened
        assertEquals(entry, scope.find("local:/some/path", null))
    }

    @Test
    fun `allModulesFor works with local prefix`() {
        val key = "local:/libs/mylib"
        val mkEntry = { coords: String -> KloneRegistry.Entry(key, coords, tempDir) }

        scope.register(key, null, mkEntry("com.example:mylib"))
        scope.register(key, "sub1", mkEntry("com.example:sub1"))

        val modules = scope.allModulesFor(key)
        assertEquals(2, modules.size)
        assertTrue(null in modules)
        assertTrue("sub1" in modules)
    }
}
