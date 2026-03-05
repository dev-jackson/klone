package dev.klone.model

data class GitDependency(
    val url: String,
    val ref: GitRef,
    val moduleCoordinates: String? = null,
    // null = auto-detect all submodules from repo settings
    // emptyList = root module only
    // listOf("x","y") = specific named submodules
    val submoduleNames: List<String>? = null
) {
    val repoName: String
        get() = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")

    val cacheKey: String
        get() {
            val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
            val path = url.removePrefix("https://").removePrefix("http://").substringAfter('/')
                .trimEnd('/').removeSuffix(".git")
            return "$host/$path"
        }
}

sealed class GitRef {
    data class Tag(val name: String) : GitRef()
    data class Branch(val name: String) : GitRef()
    data class Commit(val hash: String) : GitRef()
}
