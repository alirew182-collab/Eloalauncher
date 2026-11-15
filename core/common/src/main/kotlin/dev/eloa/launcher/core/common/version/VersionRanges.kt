package dev.eloa.launcher.core.common.version

/** A predicate over versions: produced from Maven ranges (Forge) or Fabric/Quilt predicates. */
fun interface VersionPredicate {
    fun matches(version: String): Boolean

    companion object {
        val ANY = VersionPredicate { true }
    }
}

class VersionRangeSyntaxException(message: String) : IllegalArgumentException(message)

/**
 * Maven version range as used by Forge/NeoForge mods.toml: `[1.20,1.21)`, `[47,)`,
 * `(,1.0]`, `[1.0]`, unions `[1,2),[3,)`. A bare version (`1.0`) is a *soft* requirement
 * and matches everything, exactly like Maven/Forge. `*` or empty matches everything.
 */
object MavenVersionRange {
    private data class Restriction(
        val lower: ComparableVersion?, val lowerInclusive: Boolean,
        val upper: ComparableVersion?, val upperInclusive: Boolean,
    ) {
        fun contains(v: ComparableVersion): Boolean {
            lower?.let { val c = v.compareTo(it); if (c < 0 || (c == 0 && !lowerInclusive)) return false }
            upper?.let { val c = v.compareTo(it); if (c > 0 || (c == 0 && !upperInclusive)) return false }
            return true
        }
    }

    fun parse(spec: String): VersionPredicate {
        val s = spec.trim()
        if (s.isEmpty() || s == "*") return VersionPredicate.ANY
        if (s.first() != '[' && s.first() != '(') {
            if (s.contains('[') || s.contains('(') || s.contains(']') || s.contains(')')) {
                throw VersionRangeSyntaxException("Invalid range: $spec")
            }
            return VersionPredicate.ANY
        }
        val restrictions = ArrayList<Restriction>()
        var rest = s
        while (rest.isNotEmpty()) {
            val open = rest.first()
            if (open != '[' && open != '(') throw VersionRangeSyntaxException("Invalid range: $spec")
            val closeIdx = rest.indexOfFirst { it == ']' || it == ')' }
            if (closeIdx < 0) throw VersionRangeSyntaxException("Unclosed range: $spec")
            val body = rest.substring(1, closeIdx)
            val close = rest[closeIdx]
            restrictions += parseRestriction(open, body, close, spec)
            rest = rest.substring(closeIdx + 1).trim()
            if (rest.startsWith(",")) rest = rest.substring(1).trim()
        }
        return VersionPredicate { v -> val cv = ComparableVersion(v); restrictions.any { it.contains(cv) } }
    }

    private fun parseRestriction(open: Char, body: String, close: Char, spec: String): Restriction {
        val parts = body.split(',')
        return when (parts.size) {
            1 -> {
                if (open != '[' || close != ']') throw VersionRangeSyntaxException("Single version must use []: $spec")
                val v = ComparableVersion(parts[0].trim())
                Restriction(v, true, v, true)
            }
            2 -> {
                val lo = parts[0].trim().takeIf { it.isNotEmpty() }?.let(::ComparableVersion)
                val hi = parts[1].trim().takeIf { it.isNotEmpty() }?.let(::ComparableVersion)
                if (lo != null && hi != null && lo > hi) throw VersionRangeSyntaxException("Lower bound > upper bound: $spec")
                Restriction(lo, open == '[', hi, close == ']')
            }
            else -> throw VersionRangeSyntaxException("Invalid range: $spec")
        }
    }
}

/**
 * Fabric / Quilt dependency predicates: `*`, `1.2.3` (exact), `=1.2.3`, `>=1.2`, `>1`, `<=`, `<`,
 * `~1.2` (same minor), `^1.2.3` (same major), wildcards `1.20.x`, and space separated
 * conjunctions (`>=1.2 <2`). A list of predicates (JSON array) is a disjunction.
 */
object FabricVersionPredicate {
    fun parse(spec: String): VersionPredicate {
        val s = spec.trim()
        if (s.isEmpty() || s == "*") return VersionPredicate.ANY
        val parts = s.split(Regex("\\s+")).map(::parseSingle)
        return VersionPredicate { v -> parts.all { it.matches(v) } }
    }

    fun parseAny(specs: List<String>): VersionPredicate {
        if (specs.isEmpty()) return VersionPredicate.ANY
        val ps = specs.map(::parse)
        return VersionPredicate { v -> ps.any { it.matches(v) } }
    }

    private fun parseSingle(p: String): VersionPredicate {
        val ops = listOf(">=", "<=", ">", "<", "=", "~", "^")
        val op = ops.firstOrNull { p.startsWith(it) } ?: ""
        val ver = p.removePrefix(op).trim()
        if (ver.isEmpty()) throw VersionRangeSyntaxException("Missing version in predicate: $p")
        if (ver == "*") return VersionPredicate.ANY
        if (ver.split('.').any { it == "x" || it == "X" || it == "*" }) {
            val prefix = ver.split('.').takeWhile { it != "x" && it != "X" && it != "*" }
            return VersionPredicate { v ->
                val vs = v.substringBefore('+').split('.', '-')
                prefix.indices.all { i -> vs.getOrNull(i)?.let { ComparableVersion.compare(it, prefix[i]) == 0 } == true }
            }
        }
        val target = ComparableVersion(ver)
        return when (op) {
            "", "=" -> VersionPredicate { ComparableVersion(it).compareTo(target) == 0 }
            ">=" -> VersionPredicate { ComparableVersion(it) >= target }
            "<=" -> VersionPredicate { ComparableVersion(it) <= target }
            ">" -> VersionPredicate { ComparableVersion(it) > target }
            "<" -> VersionPredicate { ComparableVersion(it) < target }
            "~" -> {
                val nums = numericParts(ver)
                val upper = ComparableVersion("${nums.getOrElse(0) { 0 }}.${nums.getOrElse(1) { 0 } + 1}")
                VersionPredicate { val v = ComparableVersion(it); v >= target && v < upper }
            }
            "^" -> {
                val nums = numericParts(ver)
                val upper = ComparableVersion("${nums.getOrElse(0) { 0 } + 1}")
                VersionPredicate { val v = ComparableVersion(it); v >= target && v < upper }
            }
            else -> throw VersionRangeSyntaxException("Unknown operator in $p")
        }
    }

    private fun numericParts(v: String): List<Int> =
        v.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
}
