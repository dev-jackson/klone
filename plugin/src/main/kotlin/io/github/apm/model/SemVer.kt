package dev.klone.model

/**
 * Lenient semantic versions for git tags ("1.4", "v1.4.2", "2.0.0-rc1") and the range syntaxes
 * Android developers already know: npm-style (`^1.4`, `~1.4.2`, `>=1.2 <2`), Gradle dynamic
 * (`1.+`, `latest.release`) and Maven intervals (`[1.0,2.0)`).
 */
data class SemVer(val parts: List<Int>, val preRelease: String?) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1   // 1.0.0 > 1.0.0-rc1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString() = parts.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val VERSION = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:[-+](.+))?$""")

        fun parse(text: String): SemVer? {
            val m = VERSION.find(text.trim()) ?: return null
            return SemVer(m.groupValues[1].split('.').map { it.toInt() }, m.groupValues[2].ifEmpty { null })
        }

        fun isRange(spec: String): Boolean {
            val s = spec.trim()
            if (s.contains("&&")) return true
            return s.startsWith("^") || s.startsWith("~") || s.startsWith(">") || s.startsWith("<") ||
                s.endsWith("+") || s.startsWith("[") || s.startsWith("(") || s.startsWith("latest.") || s == "*"
        }

        /** Highest version in [candidates] satisfying [spec]; pre-releases only when the spec names one. */
        fun <T> best(spec: String, candidates: Map<T, SemVer>): T? {
            val matches = matcher(spec)
            val allowPre = spec.contains('-')
            return candidates.entries
                .filter { (_, v) -> (allowPre || v.preRelease == null) && matches(v) }
                .maxByOrNull { it.value }?.key
        }

        fun matcher(spec: String): (SemVer) -> Boolean {
            // "A && B": every range must accept the version (requirements of several dependents)
            if (spec.contains("&&")) {
                val all = spec.split("&&").map { matcher(it) }
                return { v -> all.all { it(v) } }
            }
            val s = spec.trim()
            fun v(text: String) = parse(text) ?: throw IllegalArgumentException("Invalid version '$text' in range '$spec'")
            fun bump(base: SemVer, index: Int) =
                SemVer(base.parts.take(index + 1).mapIndexed { i, p -> if (i == index) p + 1 else p }, null)

            return when {
                s == "*" || s == "+" || s.startsWith("latest.") -> { _ -> true }
                s.startsWith("^") -> {
                    val base = v(s.drop(1))
                    // ^1.4 → <2.0 ; ^0.4 → <0.5 (0.x minors are breaking)
                    val upper = bump(base, base.parts.indexOfFirst { it != 0 }.coerceAtLeast(0).coerceAtMost(base.parts.size - 1))
                    ({ it >= base && it < upper })
                }
                s.startsWith("~") -> {
                    val base = v(s.drop(1))
                    val upper = bump(base, if (base.parts.size >= 2) 1 else 0)
                    ({ it >= base && it < upper })
                }
                s.endsWith("+") -> {
                    val prefix = s.removeSuffix("+").removeSuffix(".").split('.').filter { it.isNotEmpty() }.map { it.toInt() }
                    ({ it.parts.size >= prefix.size && it.parts.take(prefix.size) == prefix })
                }
                s.startsWith("[") || s.startsWith("(") -> {
                    val lowerInclusive = s.startsWith("[")
                    val upperInclusive = s.endsWith("]")
                    val bounds = s.substring(1, s.length - 1).split(',', limit = 2).map { it.trim() }
                    val lo = bounds[0]
                    val hi = bounds.getOrElse(1) { lo }
                    val lower = lo.takeIf { it.isNotEmpty() }?.let { v(it) }
                    val upper = hi.takeIf { it.isNotEmpty() }?.let { v(it) }
                    ({ ver ->
                        (lower == null || if (lowerInclusive) ver >= lower else ver > lower) &&
                            (upper == null || if (upperInclusive) ver <= upper else ver < upper)
                    })
                }
                else -> {
                    // Space-separated comparators: ">=1.2 <2.0"
                    val checks = s.split(Regex("""\s+""")).map { c ->
                        val op = Regex("""^(>=|<=|>|<|=)?(.+)$""").find(c)!!
                        val target = v(op.groupValues[2])
                        when (op.groupValues[1]) {
                            ">=" -> { x: SemVer -> x >= target }
                            "<=" -> { x: SemVer -> x <= target }
                            ">" -> { x: SemVer -> x > target }
                            "<" -> { x: SemVer -> x < target }
                            else -> { x: SemVer -> x.compareTo(target) == 0 }
                        }
                    }
                    ({ ver -> checks.all { it(ver) } })
                }
            }
        }

        /** Version a tag carries under [pattern] ("sdk-{version}"), or the tag itself parsed. */
        fun fromTag(tag: String, pattern: String?): SemVer? {
            if (pattern == null) return parse(tag)
            val regex = Regex("^" + pattern.split("{version}").joinToString("(.+)") { Regex.escape(it) } + "$")
            return regex.find(tag)?.groupValues?.get(1)?.let { parse(it) }
        }

        fun upToNextMajor(from: String): String {
            val v = parse(from) ?: throw IllegalArgumentException("Invalid version '$from' (expected e.g. 1.4.0)")
            return ">=$from <${v.parts[0] + 1}.0.0"
        }

        fun upToNextMinor(from: String): String {
            val v = parse(from) ?: throw IllegalArgumentException("Invalid version '$from' (expected e.g. 1.4.0)")
            return ">=$from <${v.parts[0]}.${v.parts.getOrElse(1) { 0 } + 1}.0"
        }

        fun toTag(version: String, pattern: String?): String = pattern?.replace("{version}", version) ?: version
    }
}
