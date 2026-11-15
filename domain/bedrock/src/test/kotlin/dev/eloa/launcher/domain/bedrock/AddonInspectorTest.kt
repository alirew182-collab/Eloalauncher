package dev.eloa.launcher.domain.bedrock

import dev.eloa.launcher.domain.bedrock.addon.AddonInspector
import dev.eloa.launcher.domain.bedrock.addon.AddonKind
import dev.eloa.launcher.domain.bedrock.addon.AddonLibraryChecker
import dev.eloa.launcher.domain.bedrock.addon.LibraryPack
import dev.eloa.launcher.domain.bedrock.addon.ManifestException
import dev.eloa.launcher.domain.bedrock.addon.ManifestParser
import dev.eloa.launcher.domain.bedrock.addon.PackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AddonInspectorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val inspector = AddonInspector()
    private val bpUuid = "11111111-1111-1111-1111-111111111111"
    private val bpMod = "22222222-2222-2222-2222-222222222222"
    private val rpUuid = "33333333-3333-3333-3333-333333333333"
    private val rpMod = "44444444-4444-4444-4444-444444444444"

    @Test fun `manifest parsing format 2 with comments and lang`() {
        val m = ManifestParser.parse(String(Fixtures.manifest("pack.name", bpUuid, bpMod)), "pack.name=My Pack\n")
        assertEquals("My Pack", m.name); assertEquals("1.0.0", m.version); assertEquals(PackType.BEHAVIOR, m.type)
        assertEquals("1.20.0", m.minEngineVersion); assertTrue(ManifestParser.validate(m).isEmpty())
    }

    @Test fun `manifest format 3 string versions`() {
        val m = ManifestParser.parse("""{"format_version":3,"header":{"name":"§aX","uuid":"$rpUuid","version":"2.1.0","min_engine_version":"1.21.0"},"modules":[{"type":"resources","uuid":"$rpMod","version":"2.1.0"}],"dependencies":[{"module_name":"@minecraft/server","version":"1.9.0"}]}""")
        assertEquals("X", m.name); assertEquals("2.1.0", m.version); assertEquals(PackType.RESOURCE, m.type)
        assertEquals("@minecraft/server", m.dependencies.single().moduleName)
    }

    @Test(expected = ManifestException::class) fun `manifest without header`() { ManifestParser.parse("{\"format_version\":2}") }

    @Test(expected = ManifestException::class) fun `manifest invalid json`() { ManifestParser.parse("{ not json") }

    @Test fun `invalid uuid detected`() {
        val m = ManifestParser.parse(String(Fixtures.manifest("x", "not-a-uuid", bpMod)))
        assertTrue(ManifestParser.validate(m).any { it.contains("UUID") })
    }

    @Test fun `mcpack with behavior pack`() {
        val f = Fixtures.zip(tmp.newFile("bp.mcpack"), mapOf("manifest.json" to Fixtures.manifest("BP", bpUuid, bpMod), "pack_icon.png" to ByteArray(4)))
        val a = inspector.inspect(f)
        assertEquals(AddonKind.MCPACK, a.kind); assertTrue(a.isValid); assertEquals(1, a.packs.size); assertTrue(a.packs[0].hasIcon)
    }

    @Test fun `mcaddon with nested mcpacks and folders`() {
        val rp = Fixtures.zipBytes(mapOf("manifest.json" to Fixtures.manifest("RP", rpUuid, rpMod, type = "resources")))
        val f = Fixtures.zip(tmp.newFile("a.mcaddon"), mapOf(
            "BP/manifest.json" to Fixtures.manifest("BP", bpUuid, bpMod, deps = "{\"uuid\":\"$rpUuid\",\"version\":[1,0,0]}"),
            "RP.mcpack" to rp,
        ))
        val a = inspector.inspect(f)
        assertTrue(a.findings.toString(), a.isValid)
        assertEquals(setOf(PackType.BEHAVIOR, PackType.RESOURCE), a.packs.map { it.manifest.type }.toSet())
        // dependency satisfied within the same addon
        assertTrue(AddonLibraryChecker.check(a.packs.map { it.manifest }, emptyList(), "1.21.0").none { it.code == "MISSING_DEP" })
    }

    @Test fun `duplicate uuid inside addon is error`() {
        val f = Fixtures.zip(tmp.newFile("d.mcaddon"), mapOf(
            "A/manifest.json" to Fixtures.manifest("A", bpUuid, bpMod),
            "B/manifest.json" to Fixtures.manifest("B", bpUuid, rpMod),
        ))
        assertFalse(inspector.inspect(f).isValid)
    }

    @Test fun `library checks duplicates updates deps and engine`() {
        val m = ManifestParser.parse(String(Fixtures.manifest("BP", bpUuid, bpMod, deps = "{\"uuid\":\"$rpUuid\",\"version\":[1,0,0]}", version = "[1, 2, 0]")))
        val lib = listOf(LibraryPack(bpUuid, "1.0.0", "BP", true))
        val f = AddonLibraryChecker.check(listOf(m), lib, "1.19.0")
        assertTrue(f.any { it.code == "UPDATE" }); assertTrue(f.any { it.code == "MISSING_DEP" }); assertTrue(f.any { it.code == "ENGINE" })
        assertTrue(AddonLibraryChecker.check(listOf(m), listOf(LibraryPack(bpUuid, "1.2.0", "BP", true)), null).any { it.code == "DUPLICATE" })
    }

    private fun levelDat(name: String): ByteArray {
        val nbt = Fixtures.LeNbt().beginCompound("").string("LevelName", name).int("GameType", 1).long("LastPlayed", 1700000000L)
            .intList("lastOpenedWithVersion", listOf(1, 21, 0, 3, 0)).end().bytes()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putInt(nbt.size).array()
        return ByteArrayOutputStream().apply { write(header); write(nbt) }.toByteArray()
    }

    @Test fun `mcworld parsed with level dat`() {
        val f = Fixtures.zip(tmp.newFile("w.mcworld"), mapOf(
            "level.dat" to levelDat("Castle"), "levelname.txt" to "My Castle".toByteArray(), "db/CURRENT" to ByteArray(1),
            "behavior_packs/bp/manifest.json" to Fixtures.manifest("BP", bpUuid, bpMod),
        ))
        val a = inspector.inspect(f)
        assertTrue(a.findings.toString(), a.isValid)
        val w = assertNotNull(a.world).let { a.world!! }
        assertEquals("My Castle", w.levelName); assertEquals(1, w.gameType); assertEquals("1.21.0.3.0", w.lastOpenedWithVersion); assertEquals(1, w.embeddedPacks)
        assertTrue(a.packs.isEmpty()) // embedded packs are part of the world
    }

    @Test fun `mcworld without level dat is invalid`() {
        val f = Fixtures.zip(tmp.newFile("bad.mcworld"), mapOf("foo.txt" to ByteArray(1)))
        assertFalse(inspector.inspect(f).isValid)
    }

    @Test fun `mcstructure size read`() {
        val nbt = Fixtures.LeNbt().beginCompound("").int("format_version", 1).intList("size", listOf(4, 5, 6)).end().bytes()
        val f = tmp.newFile("house.mcstructure"); f.writeBytes(nbt)
        val a = inspector.inspect(f)
        assertTrue(a.isValid); assertEquals(5, a.structure!!.sizeY)
        val bad = tmp.newFile("bad.mcstructure"); bad.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(inspector.inspect(bad).isValid)
    }

    @Test fun `corrupted and unsupported files`() {
        val t = tmp.newFile("x.mcpack"); t.writeText("garbage")
        assertFalse(inspector.inspect(t).isValid)
        val u = tmp.newFile("x.exe"); u.writeText("MZ")
        assertFalse(inspector.inspect(u).isValid)
        val empty = Fixtures.zip(tmp.newFile("e.mcpack"), mapOf("readme.txt" to ByteArray(1)))
        assertTrue(inspector.inspect(empty).findings.any { it.code == "NO_PACKS" })
    }

    @Test fun `nested corrupted pack reported`() {
        val f = Fixtures.zip(tmp.newFile("n.mcaddon"), mapOf("x.mcpack" to "PK\u0003\u0004garbage".toByteArray()))
        val a = inspector.inspect(f)
        assertFalse(a.isValid)
    }
}
