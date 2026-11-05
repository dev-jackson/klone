package dev.klone.scenarios

import dev.klone.fixtures.Projects
import dev.klone.fixtures.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MaintenanceTasksScenario : Scenario() {

    @Test
    fun `kloneList, kloneOutdated and kloneClean`() {
        val tagged = greeterWithTags("1.0.0", "1.1.0")
        val branch = remote("kit", Projects.javaLibrary("kit", "k1", group = "com.kit"))
        val host = host(files = Projects.host(
            dependencies = """
                gitImplementation("${tagged.url}", from = "1.0.0")
                gitImplementation("${branch.url}", branch = "main")
            """,
            prints = listOf("com.example.Greeter.version()", "com.kit.Kit.version()")
        ))
        host.runApp()

        val list = host.build("kloneList", "-q").output
        assertTrue(list.contains("1.1.0 @") && list.contains("[branch: main]"), list)

        assertTrue(host.build("kloneOutdated").output.contains("All git dependencies are up to date."))

        tagged.commit(Projects.javaLibrary("greeter", "1.2.0")); tagged.tag("1.2.0")
        tagged.commit(Projects.javaLibrary("greeter", "2.0.0")); tagged.tag("2.0.0")
        branch.commit(Projects.javaLibrary("kit", "k2", group = "com.kit"))
        val outdated = host.build("kloneOutdated").output
        assertTrue(outdated.contains("1.1.0 → latest 2.0.0 (allowed by version: >=1.0.0 <2.0.0: 1.2.0"), outdated)
        assertTrue(outdated.contains("branch: main moved"), outdated)
        assertTrue(outdated.contains("2 git dependencies can be updated."), outdated)

        val checkouts = { cacheDir.walkTopDown().filter { File(it, ".git").isDirectory }.toList() }
        // A checkout another project used long ago; repos this build uses are always fresh
        val stale = File(cacheDir, "github.com/old/project").also { File(it, ".git").mkdirs(); File(it, ".git/klone-last-used").writeText("0") }
        val unused = host.build("kloneClean", "--unused-days=30", "--no-watch-fs").output
        assertTrue(unused.contains("Deleted ${stale.absolutePath}"), unused)
        assertEquals(2, checkouts().size, "repos used by this build are kept")

        host.build("kloneClean", "--no-watch-fs")
        assertTrue(checkouts().isEmpty())
        host.runApp()
        host.build("kloneClean", "--all", "--no-watch-fs")
        assertFalse(cacheDir.exists())
    }
}
