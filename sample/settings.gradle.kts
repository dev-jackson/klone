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
    id("dev.klone") version "1.0.0-SNAPSHOT"
}

rootProject.name = "sample-app"
include(":app")
