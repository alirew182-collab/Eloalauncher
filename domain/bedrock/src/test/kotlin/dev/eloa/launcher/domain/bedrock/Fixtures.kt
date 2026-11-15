package dev.eloa.launcher.domain.bedrock

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Fixtures {
    fun apk(name: String): File = File(requireNotNull(javaClass.classLoader.getResource("apk/$name")) { "missing fixture $name" }.toURI())

    fun zip(target: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(target.outputStream()).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return target
    }

    fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bos.toByteArray()
    }

    fun manifest(name: String, uuid: String, moduleUuid: String, type: String = "data", deps: String = "", version: String = "[1, 0, 0]") = """
        {
          // comment allowed
          "format_version": 2,
          "header": { "name": "$name", "description": "desc", "uuid": "$uuid", "version": $version, "min_engine_version": [1, 20, 0] },
          "modules": [ { "type": "$type", "uuid": "$moduleUuid", "version": [1, 0, 0] } ],
          "dependencies": [ $deps ],
        }
    """.trimIndent().toByteArray()

    /** Little-endian NBT writer for tests. */
    class LeNbt {
        private val out = ByteArrayOutputStream()
        private fun le(n: Int, v: Long) { val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(); out.write(b, 0, n) }
        fun str(s: String) { val b = s.toByteArray(); le(2, b.size.toLong()); out.write(b) }
        fun beginCompound(name: String) = apply { out.write(10); str(name) }
        fun int(name: String, v: Int) = apply { out.write(3); str(name); le(4, v.toLong()) }
        fun long(name: String, v: Long) = apply { out.write(4); str(name); le(8, v) }
        fun string(name: String, v: String) = apply { out.write(8); str(name); str(v) }
        fun intList(name: String, vs: List<Int>) = apply { out.write(9); str(name); out.write(3); le(4, vs.size.toLong()); vs.forEach { le(4, it.toLong()) } }
        fun end() = apply { out.write(0) }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
