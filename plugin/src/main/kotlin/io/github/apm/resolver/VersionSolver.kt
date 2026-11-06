package dev.klone.resolver

import dev.klone.model.GitDependency
import dev.klone.model.GitRef
import dev.klone.model.SemVer
import org.gradle.api.GradleException

/**
 * One checkout per repo for the whole build, like SPM: the requirements every dependent declares
 * (the host and the cloned repos that use Klone themselves) become a single ref.
 */
internal object VersionSolver {

    /** [dep] as declared by [requiredBy] (the host build or a cloned repo). */
    data class Requirement(val dep: GitDependency, val requiredBy: String)

    /**
     * Versions and ranges intersect: `exact 1.2.0` and `from 1.0.0` become `=1.2.0 && >=1.0.0 <2.0.0`,
     * resolved to the highest tag both accept. Branches and commits must match exactly.
     */
    fun combine(requirements: List<Requirement>): GitDependency {
        val first = requirements.first().dep
        val refs = requirements.map { it.dep.ref }.distinct()
        val dirs = requirements.map { it.dep.dir }.distinct()
        if (dirs.size > 1) throw conflict(first.url, requirements, "different `dir` values $dirs")
        val patterns = requirements.mapNotNull { it.dep.tagPattern }.distinct()
        if (patterns.size > 1) throw conflict(first.url, requirements, "different tagPattern values $patterns")
        val pattern = patterns.firstOrNull()
        if (refs.size == 1) return first.copy(tagPattern = pattern)

        val specs = refs.map { ref ->
            when {
                ref is GitRef.Range -> ref.spec
                ref is GitRef.Tag && SemVer.parse(ref.name) != null -> "=${ref.name}"
                else -> throw conflict(first.url, requirements, "a branch, commit or non-version tag must be the same everywhere")
            }
        }.flatMap { it.split("&&") }.map { it.trim() }.distinct().sorted()
        return first.copy(ref = GitRef.Range(specs.joinToString(" && ")), tagPattern = pattern)
    }

    /** "No version of <url> satisfies every requirement" with who requires what. */
    fun noVersion(url: String, requirements: List<Requirement>, cause: String?): GradleException =
        GradleException(
            "[Klone] No version of $url satisfies every requirement:\n" + describe(requirements) +
                (cause?.let { "\n$it" } ?: "")
        )

    private fun conflict(url: String, requirements: List<Requirement>, reason: String) = GradleException(
        "[Klone] $url is required with incompatible refs ($reason):\n" + describe(requirements) +
            "\nOne checkout serves the whole build: align the declarations."
    )

    private fun describe(requirements: List<Requirement>) =
        requirements.distinctBy { it.requiredBy to it.dep.ref }.joinToString("\n") { "  ${it.requiredBy}: ${it.dep.ref.describe()}" }
}
