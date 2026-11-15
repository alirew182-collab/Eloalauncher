package dev.eloa.launcher.core.common.toml

class TomlParseException(message: String, line: Int) : IllegalArgumentException("Line $line: $message")

/**
 * Small TOML reader covering the subset used by Forge/NeoForge `mods.toml`:
 * key/value pairs, dotted keys, `[tables]`, `[[arrays.of.tables]]`, basic/literal strings,
 * multi-line strings, integers, floats, booleans, arrays and inline tables. Comments are ignored.
 * Values become: String, Long, Double, Boolean, List<Any?>, Map<String, Any?>.
 */
object Toml {
    fun parse(text: String): Map<String, Any?> = Parser(text.removePrefix("﻿")).parseDocument()

    private class Parser(private val src: String) {
        private var pos = 0
        private var line = 1
        private val root = LinkedHashMap<String, Any?>()
        private var current: MutableMap<String, Any?> = root

        fun parseDocument(): Map<String, Any?> {
            while (true) {
                skipWhitespaceAndNewlinesAndComments()
                if (eof()) break
                when {
                    startsWith("[[") -> parseArrayTableHeader()
                    peek() == '[' -> parseTableHeader()
                    else -> parseKeyValue(current)
                }
            }
            return root
        }

        private fun parseTableHeader() {
            pos++ // [
            val keys = parseKey()
            skipInlineWs()
            expect(']')
            current = navigate(root, keys)
            endOfLine()
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseArrayTableHeader() {
            pos += 2
            val keys = parseKey()
            skipInlineWs()
            expect(']'); expect(']')
            val parent = navigate(root, keys.dropLast(1))
            val last = keys.last()
            val list = when (val existing = parent[last]) {
                null -> ArrayList<Any?>().also { parent[last] = it }
                is MutableList<*> -> existing as MutableList<Any?>
                else -> fail("Key '$last' is not an array of tables")
            }
            val table = LinkedHashMap<String, Any?>()
            list.add(table)
            current = table
            endOfLine()
        }

        @Suppress("UNCHECKED_CAST")
        private fun navigate(start: MutableMap<String, Any?>, keys: List<String>): MutableMap<String, Any?> {
            var map = start
            for (k in keys) {
                map = when (val v = map[k]) {
                    null -> LinkedHashMap<String, Any?>().also { map[k] = it }
                    is MutableMap<*, *> -> v as MutableMap<String, Any?>
                    is MutableList<*> -> (v.lastOrNull() as? MutableMap<String, Any?>) ?: fail("Key '$k' is not a table")
                    else -> fail("Key '$k' already holds a value")
                }
            }
            return map
        }

        private fun parseKeyValue(target: MutableMap<String, Any?>) {
            val keys = parseKey()
            skipInlineWs()
            expect('=')
            skipInlineWs()
            val value = parseValue()
            val table = navigate(target, keys.dropLast(1))
            if (table.containsKey(keys.last())) fail("Duplicate key '${keys.last()}'")
            table[keys.last()] = value
            endOfLine()
        }

        private fun parseKey(): List<String> {
            val parts = ArrayList<String>()
            while (true) {
                skipInlineWs()
                parts += when (peek()) {
                    '"' -> parseBasicString()
                    '\'' -> parseLiteralString()
                    else -> {
                        val start = pos
                        while (!eof() && (peek().isLetterOrDigit() || peek() == '_' || peek() == '-')) pos++
                        if (start == pos) fail("Expected key")
                        src.substring(start, pos)
                    }
                }
                skipInlineWs()
                if (!eof() && peek() == '.') { pos++; continue }
                return parts
            }
        }

        private fun parseValue(): Any? {
            if (eof()) fail("Expected value")
            return when {
                startsWith("\"\"\"") -> parseMultilineBasic()
                startsWith("'''") -> parseMultilineLiteral()
                peek() == '"' -> parseBasicString()
                peek() == '\'' -> parseLiteralString()
                peek() == '[' -> parseArray()
                peek() == '{' -> parseInlineTable()
                startsWith("true") -> { pos += 4; true }
                startsWith("false") -> { pos += 5; false }
                else -> parseNumberOrDate()
            }
        }

        private fun parseArray(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            while (true) {
                skipWhitespaceAndNewlinesAndComments()
                if (eof()) fail("Unterminated array")
                if (peek() == ']') { pos++; return list }
                list += parseValue()
                skipWhitespaceAndNewlinesAndComments()
                when {
                    eof() -> fail("Unterminated array")
                    peek() == ',' -> pos++
                    peek() == ']' -> { pos++; return list }
                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        private fun parseInlineTable(): Map<String, Any?> {
            pos++
            val table = LinkedHashMap<String, Any?>()
            skipInlineWs()
            if (!eof() && peek() == '}') { pos++; return table }
            while (true) {
                val keys = parseKey()
                skipInlineWs(); expect('='); skipInlineWs()
                val v = parseValue()
                navigate(table, keys.dropLast(1))[keys.last()] = v
                skipInlineWs()
                when {
                    eof() -> fail("Unterminated inline table")
                    peek() == ',' -> { pos++; skipInlineWs() }
                    peek() == '}' -> { pos++; return table }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseNumberOrDate(): Any {
            val start = pos
            while (!eof() && peek() !in ",]}#\n\r" && !(peek() == ' ' && !looksLikeDateTimeSpace(start))) pos++
            val token = src.substring(start, pos).trim().replace("_", "")
            if (token.isEmpty()) fail("Expected value")
            token.toLongOrNull()?.let { return it }
            if (token.startsWith("0x")) token.substring(2).toLongOrNull(16)?.let { return it }
            token.toDoubleOrNull()?.let { return it }
            when (token) { "inf", "+inf" -> return Double.POSITIVE_INFINITY; "-inf" -> return Double.NEGATIVE_INFINITY; "nan", "+nan", "-nan" -> return Double.NaN }
            if (Regex("^\\d{4}-\\d{2}-\\d{2}.*").matches(token) || Regex("^\\d{2}:\\d{2}.*").matches(token)) return token
            fail("Invalid value '$token'")
        }

        private fun looksLikeDateTimeSpace(start: Int): Boolean =
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(src.substring(start, pos)) &&
                pos + 1 < src.length && src[pos + 1].isDigit()

        private fun parseBasicString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (eof() || peek() == '\n') fail("Unterminated string")
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(parseEscape())
                    else -> sb.append(c)
                }
            }
        }

        private fun parseEscape(): String {
            if (eof()) fail("Bad escape")
            return when (val e = src[pos++]) {
                'n' -> "\n"; 't' -> "\t"; 'r' -> "\r"; 'b' -> "\b"; 'f' -> "\u000C"; '"' -> "\""; '\\' -> "\\"
                'u' -> unicode(4); 'U' -> unicode(8)
                else -> fail("Invalid escape \\$e")
            }
        }

        private fun unicode(len: Int): String {
            if (pos + len > src.length) fail("Bad unicode escape")
            val cp = src.substring(pos, pos + len).toIntOrNull(16) ?: fail("Bad unicode escape")
            pos += len
            return String(Character.toChars(cp))
        }

        private fun parseLiteralString(): String {
            pos++
            val start = pos
            while (!eof() && peek() != '\'') { if (peek() == '\n') fail("Unterminated string"); pos++ }
            if (eof()) fail("Unterminated string")
            return src.substring(start, pos).also { pos++ }
        }

        private fun parseMultilineBasic(): String {
            pos += 3
            if (startsWith("\r\n")) { pos += 2; line++ } else if (startsWith("\n")) { pos++; line++ }
            val sb = StringBuilder()
            while (true) {
                if (eof()) fail("Unterminated multi-line string")
                if (startsWith("\"\"\"")) { pos += 3; return sb.toString() }
                val c = src[pos++]
                if (c == '\n') line++
                if (c == '\\') {
                    if (!eof() && (peek() == '\n' || peek() == '\r' || peek() == ' ')) {
                        while (!eof() && peek().isWhitespace()) { if (peek() == '\n') line++; pos++ }
                    } else sb.append(parseEscape())
                } else sb.append(c)
            }
        }

        private fun parseMultilineLiteral(): String {
            pos += 3
            if (startsWith("\r\n")) { pos += 2; line++ } else if (startsWith("\n")) { pos++; line++ }
            val end = src.indexOf("'''", pos)
            if (end < 0) fail("Unterminated multi-line string")
            val s = src.substring(pos, end)
            line += s.count { it == '\n' }
            pos = end + 3
            return s
        }

        private fun endOfLine() {
            skipInlineWs()
            if (eof()) return
            if (peek() == '#') { while (!eof() && peek() != '\n') pos++ }
            if (eof()) return
            if (peek() == '\r') pos++
            if (!eof() && peek() == '\n') { pos++; line++; return }
            fail("Unexpected '${peek()}' at end of line")
        }

        private fun skipInlineWs() { while (!eof() && (peek() == ' ' || peek() == '\t')) pos++ }

        private fun skipWhitespaceAndNewlinesAndComments() {
            while (!eof()) {
                when (peek()) {
                    ' ', '\t', '\r' -> pos++
                    '\n' -> { pos++; line++ }
                    '#' -> while (!eof() && peek() != '\n') pos++
                    else -> return
                }
            }
        }

        private fun expect(c: Char) { if (eof() || peek() != c) fail("Expected '$c'"); pos++ }
        private fun peek() = src[pos]
        private fun eof() = pos >= src.length
        private fun startsWith(s: String) = src.startsWith(s, pos)
        private fun fail(msg: String): Nothing = throw TomlParseException(msg, line)
    }
}
