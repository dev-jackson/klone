pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("io.github.dev-jackson.klone") version "2.0.0-beta1"
}

rootProject.name = "sample-app"
include(":app")
