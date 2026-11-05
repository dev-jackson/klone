package dev.klone

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import java.io.File

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

fun DependencyHandler.localImplementation(
    path: String,
    module: String? = null,
    modules: List<String>? = null,
    configuration: String = "implementation"
) {
    val dir = KloneRegistry.resolveLocalDir(path, KloneRegistry.rootDir ?: File(path).absoluteFile.parentFile)
    addFromRegistry(KloneRegistry.localKey(dir), path, module, modules, version = null, configuration)
}

/**
 * SPM-style git dependency. Pick one ref:
 *   from = "1.4.0"            up to next major (>=1.4.0 <2.0.0)
 *   exact = "1.4.0"           that version only
 *   upToNextMinor = "1.4.0"   >=1.4.0 <1.5.0
 *   version = "^1.4"          any range (npm, Gradle `1.+`, Maven `[1.0,2.0)`)
 *   tag / branch / commit     a literal git ref
 * The settings plugin reads these from source; at this point they only document the call.
 */
@Suppress("UNUSED_PARAMETER")
fun DependencyHandler.gitImplementation(
    url: String,
    from: String? = null,
    branch: String? = null,
    commit: String? = null,
    module: String? = null,
    modules: List<String>? = null,
    exact: String? = null,
    upToNextMinor: String? = null,
    version: String? = null,
    tag: String? = null,
    tagPattern: String? = null,
    dir: String? = null,
    configuration: String = "implementation"
) {
    // Only exact versions belong in the notation; the composite build substitutes it anyway
    addFromRegistry(url, url, module, modules, version = exact ?: tag, configuration)
}

private fun DependencyHandler.addFromRegistry(
    key: String,
    label: String,
    module: String?,
    modules: List<String>?,
    version: String?,
    configuration: String
) {
    val moduleNames: List<String?> = when {
        module != null -> listOf(module)
        modules != null -> modules
        else -> KloneRegistry.allModulesFor(key).ifEmpty { listOf(null) }
    }

    for (modName in moduleNames) {
        val coords = KloneRegistry.find(key, modName)?.moduleCoordinates ?: throw GradleException(
            "[Klone] $label#${modName ?: "root"} was not resolved in the settings phase.\n" +
                "- Apply id(\"io.github.dev-jackson.klone\") in settings.gradle.kts\n" +
                "- Use string literals or libs.versions.* (variables can't be read before configuration)\n" +
                "- Or declare it in gradle/libs.versions.toml under [metadata] klone.<alias>"
        )
        // The composite build substitutes any version; it only documents the tag
        add(configuration, if (version != null && coords.count { it == ':' } == 1) "$coords:$version" else coords)
    }
}
