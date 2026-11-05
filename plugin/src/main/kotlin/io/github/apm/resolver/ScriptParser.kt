package dev.klone.resolver

/**
 * Minimal, string-aware reader for Gradle scripts (Kotlin DSL and Groovy).
 * It does not evaluate code: it finds calls and their literal arguments, which is all the
 * settings phase can know before projects are configured.
 */
internal object ScriptParser {

    data class Call(val name: String, val args: List<Arg>)

    data class Arg(val name: String?, val expr: String)

    /** Removes `//` and `/* */` comments, leaving string literals untouched. */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end == -1) source.length else end + 2
                    out.append(' ')
                }
                c == '"' || c == '\'' -> {
                    val end = skipString(source, i)
                    out.append(source, i, end)
                    i = end
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** All calls `name(...)` with balanced parentheses — multi-line and nested calls included. */
    fun findCalls(source: String, names: Set<String>): List<Call> {
        val pattern = Regex("""(?<![\w.])(${names.joinToString("|") { Regex.escape(it) }})\s*\(""")
        return pattern.findAll(source).mapNotNull { match ->
            val open = match.range.last
            val close = matchingParen(source, open) ?: return@mapNotNull null
            Call(match.groupValues[1], splitArgs(source.substring(open + 1, close)))
        }.toList()
    }

    /** Index of the `)` closing the `(` at [open], or null when unbalanced. */
    fun matchingParen(source: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '"', '\'' -> { i = skipString(source, i); continue }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** Splits top-level comma-separated args; supports `name = value` (Kotlin) and `name: value` (Groovy). */
    fun splitArgs(argsText: String): List<Arg> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < argsText.length) {
            when (argsText[i]) {
                '"', '\'' -> { i = skipString(argsText, i); continue }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { parts += argsText.substring(start, i); start = i + 1 }
            }
            i++
        }
        parts += argsText.substring(start)
        val named = Regex("""^\s*(\w+)\s*(?:=(?!=)|:)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
        return parts.filter { it.isNotBlank() }.map { part ->
            named.find(part)?.let { Arg(it.groupValues[1], it.groupValues[2].trim()) } ?: Arg(null, part.trim())
        }
    }

    /** Value of a plain string literal (no templates), or null. */
    fun stringLiteral(expr: String): String? {
        val e = expr.trim()
        if (e.length < 2) return null
        val quote = e.first()
        if ((quote != '"' && quote != '\'') || e.last() != quote) return null
        if (e.startsWith("\"\"\"")) return null
        val body = e.substring(1, e.length - 1)
        if (quote == '"' && body.contains('$')) return null
        return body
    }

    /** Items of `listOf("a", "b")` / `["a", "b"]`, or null when not a literal list. */
    fun stringList(expr: String): List<String>? {
        val e = expr.trim()
        val inner = when {
            e.startsWith("listOf") -> e.substringAfter('(').substringBeforeLast(')')
            e.startsWith("[") && e.endsWith("]") -> e.substring(1, e.length - 1)
            else -> return null
        }
        return splitArgs(inner).map { stringLiteral(it.expr) ?: return null }
    }

    private fun skipString(source: String, start: Int): Int {
        val quote = source[start]
        if (quote == '"' && source.startsWith("\"\"\"", start)) {
            val end = source.indexOf("\"\"\"", start + 3)
            return if (end == -1) source.length else end + 3
        }
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                quote -> return i + 1
                '\n' -> return i
            }
            i++
        }
        return source.length
    }
}
