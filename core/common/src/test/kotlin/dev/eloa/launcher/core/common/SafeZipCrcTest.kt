package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.archive.CorruptArchiveException
import dev.eloa.launcher.core.common.archive.SafeZip
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeZipCrcTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A bit flip inside STORED data is invisible to java.util.zip.ZipFile; verifyIntegrity must catch it. */
    @Test fun `bit flip in stored entry is detected by CRC`() {
        val data = ByteArray(100_000) { (it * 31).toByte() }
        val f = File(tmp.root, "s.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            val e = ZipEntry("data.bin").apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size; crc = CRC32().apply { update(data) }.value }
            z.putNextEntry(e); z.write(data); z.closeEntry()
        }
        SafeZip.open(f).use { it.verifyIntegrity() }
        RandomAccessFile(f, "rw").use { it.seek(50_000); it.write(0x5A) }
        var caught = false
        try { SafeZip.open(f).use { it.verifyIntegrity() } } catch (e: CorruptArchiveException) { caught = true }
        assertTrue(caught)
    }
}
