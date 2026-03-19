package dev.klone.model

data class LocalDependency(
    val path: String,
    val moduleCoordinates: String? = null,
    val submoduleNames: List<String>? = null
)
