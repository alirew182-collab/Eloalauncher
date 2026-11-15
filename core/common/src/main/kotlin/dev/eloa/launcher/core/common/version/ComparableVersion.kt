package dev.eloa.launcher.core.common.version

/**
 * Lenient version comparator suitable for Minecraft versions ("1.20.1", "24w14a", "1.21-pre1"),
 * mod versions ("0.15.11+1.20.1") and Maven artifacts.
 *
 * Rules: split into numeric and alphabetic tokens; numbers compare numerically; well-known
 * qualifiers are ordered alpha < beta < milestone < rc/pre < snapshot < release < sp;
 * build metadata after '+' is ignored (SemVer semantics).
 */
class ComparableVersion(val raw: String) : Comparable<ComparableVersion> {
    private val tokens: List<Token> = tokenize(raw.substringBefore('+').trim().lowercase())

    private sealed interface Token
    private data class Num(val value: java.math.BigInteger) : Token
    private data class Str(val value: String) : Token

    override fun compareTo(other: ComparableVersion): Int {
        val max = maxOf(tokens.size, other.tokens.size)
        for (i in 0 until max) {
            val a = tokens.getOrNull(i)
            val b = other.tokens.getOrNull(i)
            val c = compareTokens(a, b)
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is ComparableVersion && compareTo(other) == 0
    override fun hashCode(): Int = tokens.dropLastWhile { it == Num(java.math.BigInteger.ZERO) }.hashCode()
    override fun toString(): String = raw

    companion object {
        private val QUALIFIERS = mapOf(
            "alpha" to 0, "a" to 0, "beta" to 1, "b" to 1, "milestone" to 2, "m" to 2,
            "rc" to 3, "cr" to 3, "pre" to 3, "preview" to 3, "snapshot" to 4,
            "" to 5, "ga" to 5, "final" to 5, "release" to 5, "sp" to 6,
        )

        private fun tokenize(s: String): List<Token> {
            val out = ArrayList<Token>()
            val sb = StringBuilder()
            var digit: Boolean? = null
            fun flush() {
                if (sb.isEmpty()) return
                out += if (digit == true) Num(sb.toString().toBigInteger()) else Str(sb.toString())
                sb.setLength(0)
            }
            for (ch in s) {
                when {
                    ch == '.' || ch == '-' || ch == '_' || ch == ' ' -> { flush(); digit = null }
                    ch.isDigit() -> { if (digit == false) flush(); digit = true; sb.append(ch) }
                    else -> { if (digit == true) flush(); digit = false; sb.append(ch) }
                }
            }
            flush()
            return out
        }

        /** Missing tokens are treated as 0 (numbers) or as "release" (qualifiers). */
        private fun compareTokens(a: Token?, b: Token?): Int = when {
            a == null && b == null -> 0
            a == null -> -compareTokens(b, null)
            a is Num && b == null -> a.value.signum()
            a is Str && b == null -> qualifierRank(a.value).compareTo(QUALIFIERS.getValue(""))
            a is Num && b is Num -> a.value.compareTo(b.value)
            a is Num && b is Str -> 1 // 1.0.1 > 1.0-beta
            a is Str && b is Num -> -1
            a is Str && b is Str -> {
                val ra = qualifierRank(a.value)
                val rb = qualifierRank(b.value)
                if (ra != rb) ra.compareTo(rb) else a.value.compareTo(b.value)
            }
            else -> 0
        }

        private fun qualifierRank(q: String): Int = QUALIFIERS[q] ?: 3

        fun compare(a: String, b: String): Int = ComparableVersion(a).compareTo(ComparableVersion(b))
    }
}
