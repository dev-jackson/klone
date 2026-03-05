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

    @BeforeEach
    fun setUp() {
        KloneRegistry.clear()
    }

    @Test
    fun `find returns null for unknown url`() {
        assertNull(KloneRegistry.find("https://github.com/unknown/repo"))
    }

    @Test
    fun `register and find root module with null moduleName`() {
        val entry = KloneRegistry.Entry(
            url = "https://github.com/square/retrofit",
            moduleCoordinates = "com.squareup.retrofit2:retrofit",
            localDir = tempDir
        )
        KloneRegistry.register("https://github.com/square/retrofit", null, entry)

        val found = KloneRegistry.find("https://github.com/square/retrofit", null)
        assertEquals(entry, found)
    }

    @Test
    fun `register and find named submodule`() {
        val entry = KloneRegistry.Entry(
            url = "https://github.com/square/retrofit",
            moduleCoordinates = "com.squareup.retrofit2:converter-gson",
            localDir = tempDir
        )
        KloneRegistry.register("https://github.com/square/retrofit", "converter-gson", entry)

        val found = KloneRegistry.find("https://github.com/square/retrofit", "converter-gson")
        assertEquals(entry, found)

        // Root module not registered
        assertNull(KloneRegistry.find("https://github.com/square/retrofit", null))
    }

    @Test
    fun `normalizes url with trailing slash and dot git`() {
        val entry = KloneRegistry.Entry(
            url = "https://github.com/square/retrofit",
            moduleCoordinates = "com.squareup.retrofit2:retrofit",
            localDir = tempDir
        )
        KloneRegistry.register("https://github.com/square/retrofit.git/", null, entry)

        // Both normalized and non-normalized URLs resolve to the same entry
        assertEquals(entry, KloneRegistry.find("https://github.com/square/retrofit.git/"))
        assertEquals(entry, KloneRegistry.find("https://github.com/square/retrofit"))
    }

    @Test
    fun `allModulesFor returns all registered module names for a url`() {
        val url = "https://github.com/square/retrofit"
        val mkEntry = { coords: String -> KloneRegistry.Entry(url, coords, tempDir) }

        KloneRegistry.register(url, null, mkEntry("com.squareup.retrofit2:retrofit"))
        KloneRegistry.register(url, "converter-gson", mkEntry("com.squareup.retrofit2:converter-gson"))
        KloneRegistry.register(url, "adapter-rxjava2", mkEntry("com.squareup.retrofit2:adapter-rxjava2"))

        val modules = KloneRegistry.allModulesFor(url)
        assertEquals(3, modules.size)
        assertTrue(null in modules)
        assertTrue("converter-gson" in modules)
        assertTrue("adapter-rxjava2" in modules)
    }

    @Test
    fun `allModulesFor returns empty list for unknown url`() {
        assertTrue(KloneRegistry.allModulesFor("https://github.com/unknown/repo").isEmpty())
    }
}
