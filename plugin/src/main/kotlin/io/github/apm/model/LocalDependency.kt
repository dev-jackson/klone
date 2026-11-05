package dev.klone.model

data class LocalDependency(
    val path: String,
    override val moduleCoordinates: String? = null,
    override val submoduleNames: List<String>? = null
) : ModuleSelection
