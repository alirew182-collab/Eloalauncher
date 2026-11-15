package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.io.FileOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `size copy move and atomic write`() {
        val dir = tmp.newFolder("d"); File(dir, "a").writeBytes(ByteArray(10)); File(dir, "sub").mkdirs(); File(dir, "sub/b").writeBytes(ByteArray(5))
        assertEquals(15, FileOps.sizeOf(dir))
        val copy = File(tmp.root, "copy"); FileOps.copy(dir, copy)
        assertEquals(15, FileOps.sizeOf(copy))
        val moved = File(tmp.root, "moved"); FileOps.move(copy, moved)
        assertFalse(copy.exists()); assertTrue(File(moved, "sub/b").exists())
        val t = File(tmp.root, "x/atomic.json"); FileOps.writeAtomically(t, "1".toByteArray()); FileOps.writeAtomically(t, "2".toByteArray())
        assertEquals("2", t.readText())
    }

    @Test(expected = IOException::class) fun `cannot copy dir into itself`() {
        val dir = tmp.newFolder("d"); FileOps.copy(dir, File(dir, "inner"))
    }

    @Test fun `format size`() {
        assertEquals("512 B", FileOps.formatSize(512)); assertEquals("1.5 KB", FileOps.formatSize(1536)); assertEquals("2.0 GB", FileOps.formatSize(2L shl 30))
    }
}
