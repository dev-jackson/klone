// Gradle implicitly imports org.gradle.kotlin.dsl.* in every .gradle.kts script, so these
// functions work in `dependencies {}` without an import. They delegate to dev.klone; an explicit
// `import dev.klone.gitImplementation` (older docs) still compiles and takes precedence.
package org.gradle.kotlin.dsl

import dev.klone.gitImplementation as kloneGit
import dev.klone.localImplementation as kloneLocal
import org.gradle.api.artifacts.dsl.DependencyHandler

/** Adds a git dependency resolved by the Klone settings plugin. See [dev.klone.gitImplementation]. */
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
) = kloneGit(url, from, branch, commit, module, modules, exact, upToNextMinor, version, tag, tagPattern, dir, configuration)

/** Adds a local-path dependency resolved by the Klone settings plugin. See [dev.klone.localImplementation]. */
fun DependencyHandler.localImplementation(
    path: String,
    module: String? = null,
    modules: List<String>? = null,
    configuration: String = "implementation"
) = kloneLocal(path, module, modules, configuration)

/**
 * `klone { ... }` in settings.gradle.kts. Newer Gradle generates this accessor itself; older
 * versions (7.x, early 8.x) do not, so it is provided here for every supported version.
 */
fun org.gradle.api.initialization.Settings.klone(configure: dev.klone.KloneSettingsExtension.() -> Unit) {
    extensions.getByType(dev.klone.KloneSettingsExtension::class.java).configure()
}
