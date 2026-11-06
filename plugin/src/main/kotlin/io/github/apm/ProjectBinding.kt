package dev.klone

import org.gradle.api.IsolatedAction
import org.gradle.api.Project
import org.gradle.api.invocation.Gradle
import org.gradle.util.GradleVersion
import java.io.File

/**
 * Points every project's `dependencies {}` at its own build's [KloneRegistry.Scope].
 *
 * Gradle 8.8+ uses `gradle.lifecycle.beforeProject`, the only form allowed with isolated projects;
 * its action is isolated, so it carries the root path and looks the scope up, never captures it.
 */
internal object ProjectBinding {

    fun register(gradle: Gradle, rootDir: File) {
        val rootPath = rootDir.absolutePath
        if (GradleVersion.current().baseVersion >= GradleVersion.version("8.8")) {
            Lifecycle.register(gradle, rootPath)
        } else {
            gradle.beforeProject { bind(it, rootPath) }
        }
    }

    fun bind(project: Project, rootPath: String) {
        KloneRegistry.scope(File(rootPath))?.let { KloneRegistry.bind(project.dependencies, it) }
    }

    // Separate class: never loaded on Gradle < 8.8, where IsolatedAction does not exist
    private object Lifecycle {
        fun register(gradle: Gradle, rootPath: String) = gradle.lifecycle.beforeProject(BindAction(rootPath))
    }

    private class BindAction(private val rootPath: String) : IsolatedAction<Project> {
        override fun execute(project: Project) = bind(project, rootPath)
    }
}
