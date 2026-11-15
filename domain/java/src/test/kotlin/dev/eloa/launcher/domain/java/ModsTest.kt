package dev.eloa.launcher.domain.java

import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.mods.DependencyKind
import dev.eloa.launcher.domain.java.mods.IssueSeverity
import dev.eloa.launcher.domain.java.mods.ModCompatibilityChecker
import dev.eloa.launcher.domain.java.mods.ModEnvironment
import dev.eloa.launcher.domain.java.mods.ModManager
import dev.eloa.launcher.domain.java.mods.ModMetadataException
import dev.eloa.launcher.domain.java.mods.ModMetadataReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fabric = ModEnvironment(ModLoaderType.FABRIC, "1.20.1", "0.15.11")
    private val forge = ModEnvironment(ModLoaderType.FORGE, "1.20.1", "47.2.0")

    private fun modsDir() = File(tmp.root, "mods").apply { mkdirs() }
    private fun manager() = ModManager(modsDir(), File(tmp.root, "backups"))

    @Test fun `reads fabric metadata`() {
        val m = ModMetadataReader.parseFabric("""{"schemaVersion":1,"id":"sodium","version":"0.5.8","name":"Sodium","authors":["jellysquid",{"name":"x"}],
            "depends":{"fabricloader":">=0.12","minecraft":["1.20","1.20.1"]},"breaks":{"optifabric":"*"},"jars":[{"file":"META-INF/jars/a.jar"}],"license":"LGPL-3.0"}""")
        assertEquals("sodium", m.id); assertEquals(listOf("jellysquid", "x"), m.authors)
        assertEquals(listOf("1.20", "1.20.1"), m.dependencies.first { it.modId == "minecraft" }.range)
        assertEquals(DependencyKind.INCOMPATIBLE, m.dependencies.first { it.modId == "optifabric" }.kind)
        assertEquals(1, m.nestedJars.size)
    }

    @Test fun `reads quilt metadata`() {
        val m = ModMetadataReader.parseQuilt("""{"schema_version":1,"quilt_loader":{"group":"g","id":"qmod","version":"2.0","metadata":{"name":"Q Mod","contributors":{"me":"Owner"}},
            "depends":["quilt_loader",{"id":"minecraft","versions":">=1.20"},{"id":"opt","optional":true}]},"minecraft":{"environment":"client"}}""")
        assertEquals("Q Mod", m.name); assertEquals(3, m.dependencies.size)
        assertEquals(DependencyKind.OPTIONAL, m.dependencies.first { it.modId == "opt" }.kind)
        assertEquals("client", m.environment)
    }

    @Test fun `reads forge and neoforge toml incl jarVersion placeholder`() {
        val jar = TestUtil.jar(File(tmp.root, "f.jar"), TestUtil.forgeMod("jei", "\${file.jarVersion}") + ("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nImplementation-Version: 15.2.0\n"))
        val m = ModMetadataReader.read(jar).single()
        assertEquals(ModLoaderType.FORGE, m.format); assertEquals("15.2.0", m.version); assertEquals(listOf("X", "Y"), m.authors)
        val neo = TestUtil.jar(File(tmp.root, "n.jar"), TestUtil.forgeMod("neomod", file = "META-INF/neoforge.mods.toml"))
        assertEquals(ModLoaderType.NEOFORGE, ModMetadataReader.read(neo).single().format)
        val toml = """
            modLoader="javafml"
            loaderVersion="[1,)"
            [[mods]]
            modId="x"
            [[dependencies.x]]
            modId="bad"
            type="incompatible"
            versionRange="*"
        """.trimIndent()
        assertEquals(DependencyKind.INCOMPATIBLE, ModMetadataReader.parseModsToml(toml, ModLoaderType.NEOFORGE).single().dependencies.single().kind)
    }

    @Test fun `reads legacy mcmod info`() {
        val m = ModMetadataReader.parseMcmodInfo("""[{"modid":"old","name":"Old","version":"1","mcversion":"1.7.10","authorList":["a"]}]""").single()
        assertEquals("old", m.id); assertEquals("[1.7.10]", m.dependencies.single().range.single())
    }

    @Test(expected = ModMetadataException::class) fun `broken fabric json`() { ModMetadataReader.parseFabric("{nope") }

    @Test(expected = ModMetadataException::class) fun `fabric without id`() { ModMetadataReader.parseFabric("{\"version\":\"1\"}") }

    @Test fun `manager enable disable delete import backup restore`() {
        val mgr = manager()
        val src = TestUtil.jar(File(tmp.root, "src/sodium.jar"), TestUtil.fabricMod("sodium"))
        mgr.import(src, "sodium.jar")
        var list = mgr.list()
        assertEquals(1, list.size); assertTrue(list[0].enabled); assertEquals("SODIUM", list[0].displayName)
        val newName = mgr.setEnabled("sodium.jar", false)
        assertEquals("sodium.jar.disabled", newName)
        list = mgr.list(); assertFalse(list[0].enabled)
        var dup = false
        try { mgr.import(src, "sodium.jar") } catch (_: FileAlreadyExistsException) { dup = true }
        assertTrue(dup)
        mgr.setEnabled(newName, true)
        val backup = mgr.backup()
        mgr.delete("sodium.jar")
        assertTrue(mgr.list().isEmpty())
        mgr.restore(backup)
        assertEquals(1, mgr.list().size)
        assertTrue(mgr.backups().size >= 2) // original + before-restore snapshot
    }

    @Test fun `import rejects non jar and path traversal names`() {
        val mgr = manager()
        val txt = File(tmp.root, "x.txt").apply { writeText("hi") }
        var rejected = false
        try { mgr.import(txt, "x.jar") } catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
        val src = TestUtil.jar(File(tmp.root, "src/a.jar"), TestUtil.fabricMod("a"))
        val imported = mgr.import(src, "../../evil.jar")
        assertTrue(File(modsDir(), imported.fileName).exists())
        assertFalse(File(tmp.root, "evil.jar").exists())
        var traversal = false
        try { mgr.delete("../src/a.jar") } catch (_: java.io.IOException) { traversal = true }
        assertTrue(traversal); assertTrue(src.exists())
    }

    private fun install(vararg mods: Pair<String, Map<String, String>>) = mods.forEach { (n, e) -> TestUtil.jar(File(modsDir(), n), e) }

    @Test fun `compatibility happy path fabric`() {
        install("api.jar" to TestUtil.fabricMod("fabric-api", "0.92.0"),
            "sodium.jar" to TestUtil.fabricMod("sodium", depends = "\"fabricloader\":\">=0.12\",\"minecraft\":\"~1.20\",\"fabric-api\":\"*\""))
        val issues = ModCompatibilityChecker.check(manager().list(), fabric)
        assertTrue(issues.toString(), issues.none { it.severity == IssueSeverity.ERROR })
    }

    @Test fun `detects missing deps wrong mc and loader version`() {
        install("a.jar" to TestUtil.fabricMod("a", depends = "\"fabric-api\":\"*\",\"minecraft\":\"1.21.x\",\"fabricloader\":\">=0.16\""))
        val codes = ModCompatibilityChecker.check(manager().list(), fabric).map { it.code }.toSet()
        assertTrue(codes.toString(), codes.containsAll(setOf("MISSING_DEP", "MC_VERSION", "LOADER_VERSION")))
    }

    @Test fun `detects wrong loader duplicates and breaks`() {
        install("forge.jar" to TestUtil.forgeMod("jei"),
            "a1.jar" to TestUtil.fabricMod("a", "1.0"), "a2.jar" to TestUtil.fabricMod("a", "2.0"),
            "b.jar" to TestUtil.fabricMod("b", breaks = "\"a\":\">=1.0\""))
        val codes = ModCompatibilityChecker.check(manager().list(), fabric).map { it.code }
        assertTrue(codes.toString(), "WRONG_LOADER" in codes && "DUPLICATE" in codes && "INCOMPATIBLE" in codes)
    }

    @Test fun `quilt accepts fabric mods and forge profile validates ranges`() {
        install("a.jar" to TestUtil.fabricMod("a"))
        assertTrue(ModCompatibilityChecker.check(manager().list(), ModEnvironment(ModLoaderType.QUILT, "1.20.1", "0.26")).none { it.code == "WRONG_LOADER" })
        modsDir().deleteRecursively()
        install("jei.jar" to TestUtil.forgeMod("jei", mc = "[1.20.1,1.21)", loader = "[47,)"),
            "old.jar" to TestUtil.forgeMod("old", mc = "[1.19,1.20)", loader = "[48,)"))
        val issues = ModCompatibilityChecker.check(manager().list(), forge)
        assertTrue(issues.none { it.modFile == "jei.jar" && it.severity == IssueSeverity.ERROR })
        assertTrue(issues.any { it.modFile == "old.jar" && it.code == "MC_VERSION" })
        assertTrue(issues.any { it.modFile == "old.jar" && it.code == "LOADER_VERSION" })
    }

    @Test fun `jar without metadata and corrupt jar`() {
        install("plain.jar" to mapOf("a/B.class" to "x"))
        File(modsDir(), "broken.jar").writeText("not a zip")
        val issues = ModCompatibilityChecker.check(manager().list(), fabric)
        assertTrue(issues.any { it.code == "NO_METADATA" }); assertTrue(issues.any { it.code == "UNREADABLE" })
    }

    @Test fun `vanilla profile warns about mods`() {
        install("a.jar" to TestUtil.fabricMod("a"))
        assertEquals("VANILLA_MODS", ModCompatibilityChecker.check(manager().list(), ModEnvironment(ModLoaderType.VANILLA, "1.20.1", null)).single().code)
    }

    @Test fun `stress 500 mods`() {
        repeat(500) { i -> install("m$i.jar" to TestUtil.fabricMod("mod$i", depends = if (i > 0) "\"mod${i - 1}\":\">=1.0\"" else "")) }
        val start = System.currentTimeMillis()
        val list = manager().list()
        val issues = ModCompatibilityChecker.check(list, fabric)
        assertEquals(500, list.size)
        assertTrue(issues.none { it.severity == IssueSeverity.ERROR })
        assertTrue("too slow", System.currentTimeMillis() - start < 20_000)
    }
}
