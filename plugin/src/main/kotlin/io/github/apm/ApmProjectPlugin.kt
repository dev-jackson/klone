package dev.klone

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.logging.Logging

/**
 * Project-level plugin that provides the gitImplementation() extension function.
 *
 * Usage in settings.gradle.kts:
 *   plugins { id("io.github.dev-jackson.klone") version "1.0.0" }
 *
 * Usage in app/build.gradle.kts:
 *   plugins { id("io.github.dev-jackson.klone.project") }
 *
 *   dependencies {
 *       // All submodules auto-detected from repo
 *       gitImplementation("https://github.com/square/retrofit", from = "2.9.0")
 *
 *       // Single specific submodule
 *       gitImplementation("https://github.com/square/retrofit", module = "converter-gson", from = "2.9.0")
 *
 *       // Multiple specific submodules
 *       gitImplementation("https://github.com/square/retrofit",
 *           modules = listOf("converter-gson", "adapter-rxjava2"), from = "2.9.0")
 *   }
 */
class KloneProjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Extension functions on DependencyHandler are registered via Kotlin extensions.
        // No further setup needed — functions are available at call site.
    }
}

// ---------------------------------------------------------------------------
// Extension functions — used directly in the dependencies {} block
// ---------------------------------------------------------------------------

fun DependencyHandler.gitImplementation(
    url: String,
    from: String? = null,
    branch: String? = null,
    commit: String? = null,
    module: String? = null,
    modules: List<String>? = null
) {
    val logger = Logging.getLogger("Klone")

    val requestedModuleNames: List<String?>? = when {
        module != null -> listOf(module)
        modules != null -> modules
        else -> null
    }

    val moduleNames: List<String?> = if (requestedModuleNames != null) {
        requestedModuleNames
    } else {
        val all = KloneRegistry.allModulesFor(url)
        if (all.isEmpty()) listOf(null) else all
    }

    for (modName in moduleNames) {
        val entry = KloneRegistry.find(url, modName)
        if (entry == null) {
            logger.warn("[Klone] Module not found in registry: $url#${modName ?: "root"}\n" +
                "Make sure the Klone settings plugin (dev.klone) is applied in settings.gradle.kts.")
            continue
        }

        val coords = entry.moduleCoordinates
        if (coords == null) {
            logger.warn("[Klone] Could not detect module coordinates for $url — skipping substitution.")
            continue
        }

        val notation = if (from != null && !coords.contains(":$from")) "$coords:$from" else coords
        add("implementation", notation)
    }
}
