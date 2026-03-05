package dev.klone

import dev.klone.resolver.LockFileManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LockFileManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `writes and reads klone resolved lock file`() {
        val manager = LockFileManager(tempDir)
        val entries = listOf(
            LockFileManager.LockEntry(
                url = "https://github.com/square/retrofit",
                refType = "tag",
                refValue = "2.9.0",
                resolvedCommit = "abc123def456",
                resolvedModuleCoordinates = "com.squareup.retrofit2:retrofit"
            )
        )

        manager.write(entries)

        val lockFile = File(tempDir, "klone.resolved")
        assert(lockFile.exists()) { "klone.resolved should be created" }

        val result = manager.read()
        assertNotNull(result)
        assertEquals(1, result!!.version)
        assertEquals(1, result.dependencies.size)
        with(result.dependencies[0]) {
            assertEquals("https://github.com/square/retrofit", url)
            assertEquals("tag", refType)
            assertEquals("2.9.0", refValue)
            assertEquals("abc123def456", resolvedCommit)
            assertEquals("com.squareup.retrofit2:retrofit", resolvedModuleCoordinates)
        }
    }

    @Test
    fun `returns null when lock file missing`() {
        val manager = LockFileManager(File(tempDir, "nonexistent"))
        assertEquals(null, manager.read())
    }
}
