package dev.klone.composite

import org.gradle.api.Action
import org.gradle.api.initialization.ConfigurableIncludedBuild
import org.gradle.api.initialization.Settings
import java.io.File

object CompositeIncluder {

    data class ModuleSubstitution(
        val coordinates: String,  // e.g. "com.squareup.retrofit2:converter-gson"
        val projectPath: String   // e.g. ":converter-gson" or ":" for root
    )

    /** @param buildName unique name; defaults to the directory name (two repos named `utils` would collide). */
    fun includeAll(settings: Settings, localDir: File, substitutions: List<ModuleSubstitution>, buildName: String? = null) {
        settings.includeBuild(localDir.absolutePath, Action { build: ConfigurableIncludedBuild ->
            if (buildName != null) build.name = buildName
            if (substitutions.isNotEmpty()) {
                build.dependencySubstitution { subs ->
                    for (s in substitutions) {
                        subs.substitute(subs.module(s.coordinates)).using(subs.project(s.projectPath))
                    }
                }
            }
        })
    }
}
