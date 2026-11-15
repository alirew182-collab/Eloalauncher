package dev.eloa.launcher.core.common.archive

import dev.eloa.launcher.core.common.io.PathSafety
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Limits that protect against zip bombs and absurd archives. */
data class ArchiveLimits(
    val maxEntries: Int = 50_000,
    val maxTotalUncompressed: Long = 4L * 1024 * 1024 * 1024,
    val maxEntrySize: Long = 2L * 1024 * 1024 * 1024,
    /** Maximum allowed uncompressed/compressed ratio for an entry (zip bomb heuristic). */
    val maxCompressionRatio: Int = 200,
    /** Entries smaller than this are not checked for ratio (tiny files compress very well). */
    val ratioCheckThreshold: Long = 1024 * 1024,
) {
    companion object {
        val DEFAULT = ArchiveLimits()
        /** For reading single metadata files (manifest.json, fabric.mod.json…). */
        const val MAX_METADATA_BYTES = 4 * 1024 * 1024
    }
}

class ArchiveLimitException(message: String) : IOException(message)
class CorruptArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ArchiveEntryInfo(val name: String, val size: Long, val compressedSize: Long, val isDirectory: Boolean)

/**
 * Safe read-only access to ZIP-based formats (APK, JAR, mcpack, mcworld …).
 * All entry names are validated before use; extraction is protected from Zip Slip.
 */
class SafeZip private constructor(private val zip: ZipFile, private val limits: ArchiveLimits) : AutoCloseable {

    val entries: List<ArchiveEntryInfo> by lazy {
        val result = ArrayList<ArchiveEntryInfo>()
        var total = 0L
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val entry = e.nextElement()
            if (result.size >= limits.maxEntries) throw ArchiveLimitException("Too many entries (> ${limits.maxEntries})")
            if (entry.size > 0) total += entry.size
            if (total > limits.maxTotalUncompressed) throw ArchiveLimitException("Archive expands beyond allowed size")
            result += ArchiveEntryInfo(entry.name, entry.size, entry.compressedSize, entry.isDirectory)
        }
        result
    }

    fun contains(name: String): Boolean = zip.getEntry(name) != null

    fun findEntry(predicate: (String) -> Boolean): String? = entries.firstOrNull { !it.isDirectory && predicate(it.name) }?.name

    /** Reads an entry fully, bounded by [maxBytes]. Returns null when the entry is absent. */
    fun readBytes(name: String, maxBytes: Int = ArchiveLimits.MAX_METADATA_BYTES): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        if (entry.size > maxBytes) throw ArchiveLimitException("Entry $name is too large (${entry.size} bytes)")
        return try {
            zip.getInputStream(entry).use { readBounded(it, maxBytes.toLong()) }
        } catch (ex: ZipException) {
            throw CorruptArchiveException("Corrupted entry $name", ex)
        }
    }

    fun readText(name: String, maxBytes: Int = ArchiveLimits.MAX_METADATA_BYTES): String? =
        readBytes(name, maxBytes)?.toString(Charsets.UTF_8)?.removePrefix("﻿")

    fun <T> openStream(name: String, block: (InputStream) -> T): T? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use(block)
    }

    /** Verifies every entry can be decompressed and matches its CRC. Throws on corruption. */
    fun verifyIntegrity(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val all = entries
        val buffer = ByteArray(64 * 1024)
        all.forEachIndexed { index, info ->
            if (!info.isDirectory) {
                val entry = zip.getEntry(info.name) ?: throw CorruptArchiveException("Missing entry ${info.name}")
                try {
                    zip.getInputStream(entry).use { input ->
                        var read = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > limits.maxEntrySize) throw ArchiveLimitException("Entry ${info.name} too large")
                        }
                    }
                } catch (ex: ZipException) {
                    throw CorruptArchiveException("CRC/format error in ${info.name}", ex)
                } catch (ex: java.io.EOFException) {
                    throw CorruptArchiveException("Truncated entry ${info.name}", ex)
                }
            }
            onProgress(index + 1, all.size)
        }
    }

    /**
     * Extracts entries whose names satisfy [filter] into [destination].
     * [rename] can strip prefixes; returning null skips the entry.
     */
    fun extractTo(
        destination: File,
        filter: (String) -> Boolean = { true },
        rename: (String) -> String? = { it },
    ): List<File> {
        destination.mkdirs()
        val written = ArrayList<File>()
        var total = 0L
        for (info in entries) {
            if (!filter(info.name)) continue
            val targetName = rename(info.name) ?: continue
            if (targetName.isEmpty()) continue
            val target = PathSafety.resolveInside(destination, targetName)
            if (info.isDirectory) { target.mkdirs(); continue }
            checkRatio(info)
            target.parentFile?.mkdirs()
            val entry = zip.getEntry(info.name)
            try {
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            entryBytes += n
                            total += n
                            if (entryBytes > limits.maxEntrySize) throw ArchiveLimitException("Entry ${info.name} too large")
                            if (total > limits.maxTotalUncompressed) throw ArchiveLimitException("Archive expands beyond allowed size")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (ex: ZipException) {
                target.delete()
                throw CorruptArchiveException("Corrupted entry ${info.name}", ex)
            } catch (ex: IOException) {
                target.delete()
                throw ex
            }
            written += target
        }
        return written
    }

    private fun checkRatio(info: ArchiveEntryInfo) {
        if (info.size > limits.ratioCheckThreshold && info.compressedSize > 0 &&
            info.size / info.compressedSize > limits.maxCompressionRatio
        ) throw ArchiveLimitException("Suspicious compression ratio for ${info.name}")
        if (info.size > limits.maxEntrySize) throw ArchiveLimitException("Entry ${info.name} too large")
    }

    override fun close() = zip.close()

    companion object {
        fun open(file: File, limits: ArchiveLimits = ArchiveLimits.DEFAULT): SafeZip {
            if (!file.isFile) throw IOException("File not found: ${file.name}")
            return try {
                SafeZip(ZipFile(file), limits)
            } catch (ex: ZipException) {
                throw CorruptArchiveException("Not a valid ZIP archive: ${file.name}", ex)
            }
        }

        fun isZip(file: File): Boolean = try {
            file.inputStream().use { s ->
                val h = ByteArray(4)
                s.read(h) == 4 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte()
            }
        } catch (_: IOException) { false }

        private fun readBounded(input: InputStream, max: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw ArchiveLimitException("Entry exceeds $max bytes")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** Streams a nested archive (e.g. APK inside .apks) without trusting its declared sizes. */
        fun listStream(input: InputStream, limits: ArchiveLimits = ArchiveLimits.DEFAULT): List<String> {
            val names = ArrayList<String>()
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    names += entry.name
                    if (names.size > limits.maxEntries) throw ArchiveLimitException("Too many entries")
                    entry = zis.nextEntry
                }
            }
            return names
        }
    }
}
