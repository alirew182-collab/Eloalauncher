package dev.eloa.launcher.domain.bedrock.nbt

import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NbtException(message: String) : IllegalArgumentException(message)

/** Minimal NBT tree. Compounds become Map, lists List, arrays primitive arrays. */
object Nbt {
    private const val MAX_DEPTH = 512
    private const val MAX_ARRAY = 16 * 1024 * 1024

    /** Reads a root compound in little-endian (Bedrock) byte order. */
    fun readLittleEndian(input: InputStream): Pair<String, Map<String, Any?>> = read(LeInput(input))

    /** Reads a root compound in big-endian (Java Edition) byte order. */
    fun readBigEndian(input: InputStream): Pair<String, Map<String, Any?>> = read(BeInput(DataInputStream(input)))

    private interface In {
        fun byte(): Int
        fun short(): Int
        fun int(): Int
        fun long(): Long
        fun bytes(n: Int): ByteArray
    }

    private class LeInput(private val s: InputStream) : In {
        private fun need(n: Int): ByteBuffer { val b = bytes(n); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN) }
        override fun byte(): Int { val v = s.read(); if (v < 0) throw EOFException(); return v.toByte().toInt() }
        override fun short(): Int = need(2).short.toInt()
        override fun int(): Int = need(4).int
        override fun long(): Long = need(8).long
        override fun bytes(n: Int): ByteArray {
            val out = ByteArray(n); var off = 0
            while (off < n) { val r = s.read(out, off, n - off); if (r < 0) throw EOFException(); off += r }
            return out
        }
    }

    private class BeInput(private val d: DataInput) : In {
        override fun byte() = d.readByte().toInt()
        override fun short() = d.readShort().toInt()
        override fun int() = d.readInt()
        override fun long() = d.readLong()
        override fun bytes(n: Int) = ByteArray(n).also { d.readFully(it) }
    }

    private fun read(input: In): Pair<String, Map<String, Any?>> {
        try {
            val type = input.byte()
            if (type != 10) throw NbtException("Root tag is not a compound (type $type)")
            val name = string(input)
            @Suppress("UNCHECKED_CAST")
            return name to (payload(input, 10, 0) as Map<String, Any?>)
        } catch (e: EOFException) {
            throw NbtException("Unexpected end of NBT data")
        }
    }

    private fun string(input: In): String {
        val len = input.short() and 0xFFFF
        return String(input.bytes(len), Charsets.UTF_8)
    }

    private fun checkLen(n: Int): Int { if (n < 0 || n > MAX_ARRAY) throw NbtException("Invalid length $n"); return n }

    private fun payload(input: In, type: Int, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw NbtException("NBT nested too deeply")
        return when (type) {
            1 -> input.byte().toByte()
            2 -> input.short().toShort()
            3 -> input.int()
            4 -> input.long()
            5 -> java.lang.Float.intBitsToFloat(input.int())
            6 -> java.lang.Double.longBitsToDouble(input.long())
            7 -> input.bytes(checkLen(input.int()))
            8 -> string(input)
            9 -> {
                val elemType = input.byte()
                val n = checkLen(input.int())
                if (elemType == 0 && n > 0) throw NbtException("List of End tags")
                List(n) { payload(input, elemType, depth + 1) }
            }
            10 -> {
                val map = LinkedHashMap<String, Any?>()
                while (true) {
                    val t = input.byte()
                    if (t == 0) break
                    if (t !in 1..12) throw NbtException("Unknown tag type $t")
                    val key = string(input)
                    map[key] = payload(input, t, depth + 1)
                }
                map
            }
            11 -> IntArray(checkLen(input.int())) { input.int() }
            12 -> LongArray(checkLen(input.int())) { input.long() }
            else -> throw NbtException("Unknown tag type $type")
        }
    }
}
