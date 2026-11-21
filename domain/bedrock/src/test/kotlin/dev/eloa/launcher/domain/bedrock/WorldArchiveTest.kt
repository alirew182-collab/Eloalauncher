package dev.eloa.launcher.domain.bedrock

import dev.eloa.launcher.domain.bedrock.world.WorldArchive
import dev.eloa.launcher.domain.bedrock.world.WorldArchiveException
import dev.eloa.launcher.domain.bedrock.world.WorldFile
import dev.eloa.launcher.domain.bedrock.world.WorldProblem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WorldArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun levelDat(name: String): ByteArray {
        val nbt = Fixtures.LeNbt().beginCompound("").string("LevelName", name).int("GameType", 0).long("LastPlayed", 1_700_000_000L)
            .intList("lastOpenedWithVersion", listOf(1, 21, 50, 0, 0)).end().bytes()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putInt(nbt.size).array() + nbt
    }

    private fun worldDir(): File {
        val d = tmp.newFolder("Bx7AAAAAAA=")
        File(d, "level.dat").writeBytes(levelDat("Castle"))
        File(d, "levelname.txt").writeText("My Castle")
        File(d, "world_icon.jpeg").writeBytes(byteArrayOf(-1, -40, -1, 1, 2, 3))
        File(d, "db").mkdirs(); File(d, "db/CURRENT").writeText("MANIFEST-000001"); File(d, "db/000005.ldb").writeBytes(java.util.Random(42).let { r -> ByteArray(300_000).also(r::nextBytes) })
        return d
    }

    @Test fun `backup, inspect, verify and restore round trip`() {
        val src = worldDir()
        val out = File(tmp.root, "backups/castle.mcworld")
        val sha = WorldArchive.pack(WorldArchive.filesOf(src), out)
        val meta = WorldArchive.inspect(out)
        assertEquals("My Castle", meta.levelName); assertEquals(0, meta.gameType); assertEquals("1.21.50.0.0", meta.lastOpenedWithVersion)
        assertEquals(1_700_000_000_000L, meta.lastPlayed); assertTrue(meta.hasDb); assertEquals(5, meta.fileCount)
        assertArrayEquals(byteArrayOf(-1, -40, -1, 1, 2, 3), meta.thumbnail)
        assertTrue(WorldArchive.verify(out, sha).ok)

        val target = tmp.newFolder("restored")
        WorldArchive.restore(out) { path, input -> File(target, path).apply { parentFile.mkdirs() }.outputStream().use { input.copyTo(it) } }
        src.walkTopDown().filter { it.isFile }.forEach { f -> assertArrayEquals(f.readBytes(), File(target, f.relativeTo(src).path).readBytes()) }
    }

    @Test fun `damaged backups are detected and never restored`() {
        val out = File(tmp.root, "w.mcworld")
        val sha = WorldArchive.pack(WorldArchive.filesOf(worldDir()), out)
        RandomAccessFile(out, "rw").use { it.seek(out.length() / 2); it.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        val v = WorldArchive.verify(out, sha)
        assertFalse(v.ok)
        assertTrue(WorldProblem.CHECKSUM_MISMATCH in v.problems)
        assertTrue(WorldProblem.CORRUPT_ARCHIVE in v.problems)
        var restored = 0
        try { WorldArchive.restore(out) { _, _ -> restored++ } } catch (e: WorldArchiveException) { }
        assertEquals(0, restored)
    }

    @Test fun `non-worlds and unsafe paths are rejected`() {
        val noWorld = tmp.newFolder("plain"); File(noWorld, "readme.txt").writeText("x")
        val e = runCatching { WorldArchive.pack(WorldArchive.filesOf(noWorld), File(tmp.root, "x.mcworld")) }.exceptionOrNull()
        assertEquals(WorldProblem.NO_LEVEL_DAT, (e as WorldArchiveException).problem)
        assertFalse(File(tmp.root, "x.mcworld").exists())
        val evil = sequenceOf(WorldFile("../../escape", 1) { "x".byteInputStream() })
        val e2 = runCatching { WorldArchive.pack(evil, File(tmp.root, "y.mcworld")) }.exceptionOrNull()
        assertEquals(WorldProblem.UNSAFE_PATH, (e2 as WorldArchiveException).problem)
        listOf("a/../b", "/abs", "c:\\x", "", "ok/./x").forEach { assertFalse(it, WorldArchive.isSafePath(it)) }
        assertTrue(WorldArchive.isSafePath("db/000005.ldb"))
    }

    @Test fun `world nested one folder deep is supported`() {
        val z = Fixtures.zip(File(tmp.root, "n.mcworld"), mapOf("MyWorld/level.dat" to levelDat("Nested"), "MyWorld/db/CURRENT" to ByteArray(2)))
        assertEquals("Nested", WorldArchive.inspect(z).levelName)
        val paths = ArrayList<String>()
        WorldArchive.restore(z) { p, _ -> paths += p }
        assertEquals(setOf("level.dat", "db/CURRENT"), paths.toSet())
    }
}
