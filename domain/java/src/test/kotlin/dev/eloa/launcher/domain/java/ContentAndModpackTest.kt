package dev.eloa.launcher.domain.java

import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.domain.java.content.ContentValidator
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.domain.java.content.PackFormats
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.modpack.ModpackException
import dev.eloa.launcher.domain.java.modpack.Mrpack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContentAndModpackTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `pack format table`() {
        assertEquals(15, PackFormats.resourceFormatFor("1.20.1"))
        assertEquals(34, PackFormats.resourceFormatFor("1.21"))
        assertEquals(3, PackFormats.resourceFormatFor("1.12.2"))
        assertNull(PackFormats.resourceFormatFor("24w14a"))
        assertNull(PackFormats.resourceFormatFor("1.99"))
    }

    @Test fun `pack meta compatibility incl supported formats`() {
        val f = TestUtil.jar(File(tmp.root, "rp.zip"), mapOf("pack.mcmeta" to """{"pack":{"pack_format":15,"description":"§aHi"}}""", "assets/x.png" to "x"))
        SafeZip.open(f).use { z ->
            val m = PackFormats.readPackMeta(z)!!
            assertEquals("Hi", m.description)
            assertEquals(PackFormats.Compat.COMPATIBLE, PackFormats.compatibility(m, "1.20.1"))
            assertEquals(PackFormats.Compat.INCOMPATIBLE, PackFormats.compatibility(m, "1.21"))
            assertEquals(PackFormats.Compat.UNKNOWN, PackFormats.compatibility(m, "25w02a"))
        }
        val ranged = TestUtil.jar(File(tmp.root, "rp2.zip"), mapOf("pack.mcmeta" to """{"pack":{"pack_format":15,"description":{"text":"T"},"supported_formats":[15,34]}}"""))
        SafeZip.open(ranged).use { z -> assertEquals(PackFormats.Compat.COMPATIBLE, PackFormats.compatibility(PackFormats.readPackMeta(z)!!, "1.21")) }
    }

    @Test fun `content validation`() {
        val rp = TestUtil.jar(File(tmp.root, "a.zip"), mapOf("pack.mcmeta" to "{}"))
        assertTrue(ContentValidator.validate(JavaContentType.RESOURCE_PACKS, rp, "a.zip").ok)
        assertFalse(ContentValidator.validate(JavaContentType.DATAPACKS, rp, "a.zip").ok)
        val shader = TestUtil.jar(File(tmp.root, "s.zip"), mapOf("shaders/final.fsh" to "x"))
        assertTrue(ContentValidator.validate(JavaContentType.SHADER_PACKS, shader, "s.zip").ok)
        assertFalse(ContentValidator.validate(JavaContentType.RESOURCE_PACKS, shader, "s.zip").ok)
        val world = TestUtil.jar(File(tmp.root, "w.zip"), mapOf("MyWorld/level.dat" to "x", "MyWorld/region/r.0.0.mca" to "y"))
        assertTrue(ContentValidator.validate(JavaContentType.WORLDS, world, "w.zip").ok)
        val cfg = File(tmp.root, "c.toml").apply { writeText("a=1") }
        assertTrue(ContentValidator.validate(JavaContentType.CONFIGS, cfg, "c.toml").ok)
        val bin = File(tmp.root, "b.json").apply { writeBytes(byteArrayOf(1, 0, 2)) }
        assertFalse(ContentValidator.validate(JavaContentType.CONFIGS, bin, "b.json").ok)
        assertFalse(ContentValidator.validate(JavaContentType.SHADER_PACKS, rp, "a.exe").ok)
    }

    private fun mrpack(index: String, extra: Map<String, String> = emptyMap()) =
        TestUtil.jar(File(tmp.root, "p${System.nanoTime()}.mrpack"), mapOf("modrinth.index.json" to index) + extra)

    @Test fun `reads mrpack and extracts overrides`() {
        val sha = "a".repeat(40)
        val f = mrpack("""{"formatVersion":1,"game":"minecraft","versionId":"1","name":"Pack","files":[
            {"path":"mods/a.jar","hashes":{"sha1":"$sha","sha512":"x"},"downloads":["https://cdn.modrinth.com/data/a.jar"],"fileSize":10},
            {"path":"mods/server.jar","hashes":{"sha1":"$sha"},"env":{"client":"unsupported","server":"required"},"downloads":["https://cdn.modrinth.com/s.jar"]}],
            "dependencies":{"minecraft":"1.20.1","forge":"47.2.0"}}""",
            mapOf("overrides/config/a.toml" to "x=1", "client-overrides/options.txt" to "fov:1"))
        val plan = Mrpack.read(f)
        assertEquals(ModLoaderType.FORGE, plan.loader); assertEquals("1.20.1-47.2.0", plan.loaderVersion); assertEquals(1, plan.files.size)
        val game = tmp.newFolder("game")
        assertEquals(2, Mrpack.extractOverrides(f, game))
        assertTrue(File(game, "config/a.toml").isFile); assertTrue(File(game, "options.txt").isFile)
        assertTrue(Mrpack.POLICY.isAllowed(okhttp3.HttpUrl.Builder().scheme("https").host("cdn.modrinth.com").build()))
        assertFalse(Mrpack.POLICY.isAllowed(okhttp3.HttpUrl.Builder().scheme("https").host("evil.example").build()))
    }

    @Test(expected = ModpackException::class) fun `mrpack path traversal rejected`() {
        Mrpack.read(mrpack("""{"formatVersion":1,"game":"minecraft","name":"P","files":[{"path":"../../x.jar","hashes":{"sha1":"${"a".repeat(40)}"},"downloads":["https://cdn.modrinth.com/x"]}],"dependencies":{"minecraft":"1.20.1"}}"""))
    }

    @Test(expected = ModpackException::class) fun `mrpack without minecraft`() {
        Mrpack.read(mrpack("""{"formatVersion":1,"game":"minecraft","name":"P","files":[],"dependencies":{}}"""))
    }

    @Test(expected = ModpackException::class) fun `not an mrpack`() { Mrpack.read(TestUtil.jar(File(tmp.root, "x.mrpack"), mapOf("a" to "b"))) }
}
