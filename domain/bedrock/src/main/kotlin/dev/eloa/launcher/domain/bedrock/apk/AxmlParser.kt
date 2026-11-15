package dev.eloa.launcher.domain.bedrock.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlParseException(message: String) : IllegalArgumentException(message)

/** Typed attribute value from a compiled (binary) Android XML document. */
sealed interface AxmlValue {
    data class Str(val value: String) : AxmlValue
    data class IntValue(val value: Int) : AxmlValue
    data class Bool(val value: Boolean) : AxmlValue
    data class Reference(val resourceId: Int) : AxmlValue
    data class Other(val type: Int, val data: Int) : AxmlValue

    fun asInt(): Int? = when (this) {
        is IntValue -> value
        is Str -> value.trim().toIntOrNull()
        is Bool -> if (value) 1 else 0
        else -> null
    }

    fun asString(): String? = when (this) {
        is Str -> value
        is IntValue -> value.toString()
        is Bool -> value.toString()
        else -> null
    }
}

data class AxmlAttribute(val namespace: String?, val name: String, val resourceId: Int, val value: AxmlValue)

data class AxmlElement(val name: String, val depth: Int, val attributes: List<AxmlAttribute>) {
    /** Finds an attribute by android resource id first (robust against obfuscated names), then by name. */
    fun attr(name: String, resourceId: Int = 0): AxmlValue? =
        (if (resourceId != 0) attributes.firstOrNull { it.resourceId == resourceId } else null)?.value
            ?: attributes.firstOrNull { it.name == name }?.value
}

/**
 * Parser for Android's binary XML (AXML) format used by AndroidManifest.xml inside APKs.
 * Handles UTF-8 and UTF-16 string pools and resource-id based attribute lookup.
 * All offsets are bounds-checked: malformed input yields [AxmlParseException], never a crash.
 */
object AxmlParser {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val UTF8_FLAG = 1 shl 8

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    fun parse(bytes: ByteArray): List<AxmlElement> = try {
        parseUnchecked(bytes)
    } catch (e: IndexOutOfBoundsException) {
        throw AxmlParseException("Malformed binary XML: offset out of range")
    }

    private fun parseUnchecked(bytes: ByteArray): List<AxmlElement> {
        if (bytes.size < 8) throw AxmlParseException("File too small")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.getShort(0).toInt() and 0xFFFF
        if (type != RES_XML_TYPE) throw AxmlParseException("Not a binary XML document (type=0x${type.toString(16)})")
        val headerSize = buf.getShort(2).toInt() and 0xFFFF
        val total = minOf(buf.getInt(4).toLong() and 0xFFFFFFFFL, bytes.size.toLong()).toInt()
        var strings: List<String> = emptyList()
        var resourceIds = IntArray(0)
        val elements = ArrayList<AxmlElement>()
        var depth = 0
        var offset = headerSize
        while (offset + 8 <= total) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val chunkHeader = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < 8 || offset.toLong() + chunkSize > total) throw AxmlParseException("Invalid chunk size at $offset")
            when (chunkType) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(buf, offset, chunkSize)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - chunkHeader) / 4
                    resourceIds = IntArray(count) { buf.getInt(offset + chunkHeader + it * 4) }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    elements += readStartElement(buf, offset, chunkHeader, chunkSize, strings, resourceIds, depth)
                    depth++
                }
                RES_XML_END_ELEMENT_TYPE -> depth = maxOf(0, depth - 1)
            }
            offset += chunkSize
        }
        if (elements.isEmpty()) throw AxmlParseException("No elements in document")
        return elements
    }

    private fun readStringPool(buf: ByteBuffer, start: Int, size: Int): List<String> {
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        if (count < 0 || count > 1_000_000) throw AxmlParseException("Invalid string count $count")
        val utf8 = flags and UTF8_FLAG != 0
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val end = start + size
        return List(count) { i ->
            val offPos = start + headerSize + i * 4
            if (offPos + 4 > end) throw AxmlParseException("String offset out of bounds")
            val strOff = start + stringsStart + buf.getInt(offPos)
            if (strOff < start || strOff >= end) throw AxmlParseException("String out of bounds")
            if (utf8) readUtf8(buf, strOff, end) else readUtf16(buf, strOff, end)
        }
    }

    private fun readUtf8(buf: ByteBuffer, pos0: Int, end: Int): String {
        var pos = pos0
        fun len(): Int {
            val b0 = buf.get(pos++).toInt() and 0xFF
            return if (b0 and 0x80 != 0) ((b0 and 0x7F) shl 8) or (buf.get(pos++).toInt() and 0xFF) else b0
        }
        len() // utf16 length (unused)
        val byteLen = len()
        if (pos.toLong() + byteLen > end) throw AxmlParseException("UTF-8 string out of bounds")
        val arr = ByteArray(byteLen)
        for (i in 0 until byteLen) arr[i] = buf.get(pos + i)
        return String(arr, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, pos0: Int, end: Int): String {
        var pos = pos0
        var len = buf.getShort(pos).toInt() and 0xFFFF
        pos += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (buf.getShort(pos).toInt() and 0xFFFF)
            pos += 2
        }
        if (len < 0 || pos.toLong() + len.toLong() * 2 > end) throw AxmlParseException("UTF-16 string out of bounds")
        val chars = CharArray(len) { buf.getChar(pos + it * 2) }
        return String(chars)
    }

    private fun readStartElement(
        buf: ByteBuffer, start: Int, headerSize: Int, size: Int,
        strings: List<String>, resourceIds: IntArray, depth: Int,
    ): AxmlElement {
        val ext = start + headerSize
        if (ext + 20 > start + size) throw AxmlParseException("Truncated element")
        fun str(idx: Int): String? = if (idx < 0 || idx >= strings.size) null else strings[idx]
        val name = str(buf.getInt(ext + 4)) ?: throw AxmlParseException("Element without name")
        val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
        val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
        val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
        val attrs = ArrayList<AxmlAttribute>(attrCount)
        for (i in 0 until attrCount) {
            val a = ext + attrStart + i * attrSize
            if (a + 20 > start + size) throw AxmlParseException("Attribute out of bounds")
            val nsIdx = buf.getInt(a)
            val nameIdx = buf.getInt(a + 4)
            val rawIdx = buf.getInt(a + 8)
            val dataType = buf.get(a + 15).toInt() and 0xFF
            val data = buf.getInt(a + 16)
            val value = when (dataType) {
                TYPE_STRING -> AxmlValue.Str(str(data) ?: str(rawIdx) ?: "")
                TYPE_INT_DEC, TYPE_INT_HEX -> AxmlValue.IntValue(data)
                TYPE_INT_BOOLEAN -> AxmlValue.Bool(data != 0)
                TYPE_REFERENCE -> AxmlValue.Reference(data)
                else -> str(rawIdx)?.let { AxmlValue.Str(it) } ?: AxmlValue.Other(dataType, data)
            }
            val resId = if (nameIdx in resourceIds.indices) resourceIds[nameIdx] else 0
            attrs += AxmlAttribute(str(nsIdx), str(nameIdx) ?: "", resId, value)
        }
        return AxmlElement(name, depth, attrs)
    }
}
