package dev.klone.composite

import dev.klone.model.GitDependency
import org.gradle.api.Action
import org.gradle.api.initialization.ConfigurableIncludedBuild
import org.gradle.api.initialization.Settings
import java.io.File

object CompositeIncluder {

    data class ModuleSubstitution(
        val coordinates: String,  // e.g. "com.squareup.retrofit2:converter-gson"
        val projectPath: String   // e.g. ":converter-gson" or ":" for root
    )

    fun include(settings: Settings, dep: GitDependency, localDir: File, resolvedModule: String?) {
        val moduleCoordinates = dep.moduleCoordinates ?: resolvedModule

        if (moduleCoordinates != null) {
            settings.includeBuild(localDir.absolutePath, Action { build: ConfigurableIncludedBuild ->
                build.dependencySubstitution { subs ->
                    subs.substitute(subs.module(moduleCoordinates)).using(subs.project(":"))
                }
            })
        } else {
            settings.includeBuild(localDir.absolutePath)
        }
    }

    fun includeAll(settings: Settings, localDir: File, substitutions: List<ModuleSubstitution>) {
        settings.includeBuild(localDir.absolutePath, Action { build: ConfigurableIncludedBuild ->
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
