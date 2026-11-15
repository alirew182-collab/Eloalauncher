package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.archive.ArchiveLimitException
import dev.eloa.launcher.core.common.archive.ArchiveLimits
import dev.eloa.launcher.core.common.archive.CorruptArchiveException
import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.io.PathTraversalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeZipTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile("t${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return f
    }

    @Test fun `reads and extracts entries`() {
        val f = zip("manifest.json" to "{}".toByteArray(), "dir/a.txt" to "hello".toByteArray())
        SafeZip.open(f).use { z ->
            assertEquals(2, z.entries.size)
            assertEquals("{}", z.readText("manifest.json"))
            assertNull(z.readText("missing"))
            val out = tmp.newFolder("out")
            val written = z.extractTo(out)
            assertEquals(2, written.size)
            assertEquals("hello", File(out, "dir/a.txt").readText())
        }
    }

    @Test fun `zip slip entry is refused and nothing escapes`() {
        val f = zip("../../evil.sh" to "rm -rf".toByteArray())
        val out = tmp.newFolder("out")
        var thrown = false
        SafeZip.open(f).use { z -> try { z.extractTo(out) } catch (_: PathTraversalException) { thrown = true } }
        assertTrue(thrown)
        assertFalse(File(out.parentFile.parentFile, "evil.sh").exists())
    }

    @Test fun `entry count limit enforced`() {
        val entries = (0 until 20).map { "f$it" to ByteArray(1) }.toTypedArray()
        val f = zip(*entries)
        var thrown = false
        SafeZip.open(f, ArchiveLimits(maxEntries = 10)).use { z -> try { z.entries } catch (_: ArchiveLimitException) { thrown = true } }
        assertTrue(thrown)
    }

    @Test fun `zip bomb ratio detected`() {
        val f = zip("bomb.bin" to ByteArray(5 * 1024 * 1024))
        var thrown = false
        SafeZip.open(f).use { z -> try { z.extractTo(tmp.newFolder("o")) } catch (_: ArchiveLimitException) { thrown = true } }
        assertTrue(thrown)
    }

    @Test fun `total size limit enforced`() {
        val f = zip("a" to ByteArray(600), "b" to ByteArray(600))
        var thrown = false
        SafeZip.open(f, ArchiveLimits(maxTotalUncompressed = 1000)).use { z -> try { z.entries } catch (_: ArchiveLimitException) { thrown = true } }
        assertTrue(thrown)
    }

    @Test fun `metadata read is bounded`() {
        val f = zip("big.json" to ByteArray(2000))
        var thrown = false
        SafeZip.open(f).use { z -> try { z.readBytes("big.json", maxBytes = 1000) } catch (_: ArchiveLimitException) { thrown = true } }
        assertTrue(thrown)
    }

    @Test(expected = CorruptArchiveException::class) fun `non zip file is reported as corrupt`() {
        val f = tmp.newFile("x.zip"); f.writeText("not a zip")
        SafeZip.open(f)
    }

    @Test fun `truncated zip detected by integrity check or open`() {
        val good = zip("data.bin" to ByteArray(200_000) { (it % 251).toByte() })
        val bytes = good.readBytes()
        val bad = tmp.newFile("bad.zip"); bad.writeBytes(bytes.copyOf(bytes.size / 2))
        var detected = false
        try { SafeZip.open(bad).use { it.verifyIntegrity() } } catch (_: java.io.IOException) { detected = true }
        assertTrue(detected)
    }

    @Test fun `corrupted payload fails CRC`() {
        val good = zip("data.txt" to "A".repeat(1000).toByteArray())
        val bytes = good.readBytes()
        // Flip bytes inside compressed data region (after local header ~ 30 + name length).
        for (i in 40 until 50) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        val bad = tmp.newFile("crc.zip"); bad.writeBytes(bytes)
        var detected = false
        try { SafeZip.open(bad).use { it.verifyIntegrity() } } catch (_: java.io.IOException) { detected = true }
        assertTrue(detected)
    }

    @Test fun `isZip detects magic`() {
        assertTrue(SafeZip.isZip(zip("a" to ByteArray(1))))
        val t = tmp.newFile("t.txt"); t.writeText("hello")
        assertFalse(SafeZip.isZip(t))
    }
}
