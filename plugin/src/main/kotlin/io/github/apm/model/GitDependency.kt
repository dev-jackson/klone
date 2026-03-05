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
            // SSH: git@github.com:user/repo.git
            if (url.startsWith("git@")) {
                val host = url.removePrefix("git@").substringBefore(':')
                val path = url.substringAfter(':').trimEnd('/').removeSuffix(".git")
                return "$host/$path"
            }
            // HTTPS — strip scheme and embedded auth (user:token@host)
            val noScheme = url.removePrefix("https://").removePrefix("http://")
            val noAuth = if (noScheme.contains('@')) noScheme.substringAfter('@') else noScheme
            val host = noAuth.substringBefore('/')
            val path = noAuth.substringAfter('/').trimEnd('/').removeSuffix(".git")
            return "$host/$path"
        }
}

sealed class GitRef {
    data class Tag(val name: String) : GitRef()
    data class Branch(val name: String) : GitRef()
    data class Commit(val hash: String) : GitRef()
}
