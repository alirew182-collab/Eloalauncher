package dev.eloa.launcher

import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.eloa.launcher.core.data.db.BedrockDao
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.mods.IssueSeverity
import dev.eloa.launcher.feature.addons.AddonRepository
import dev.eloa.launcher.feature.bedrock.BedrockRepository
import dev.eloa.launcher.feature.diagnostics.DiagnosticsEngine
import dev.eloa.launcher.feature.files.FileManagerRepository
import dev.eloa.launcher.feature.files.FileRoot
import dev.eloa.launcher.feature.files.FileSort
import dev.eloa.launcher.feature.java.ContentRepository
import dev.eloa.launcher.feature.java.ModpackRepository
import dev.eloa.launcher.feature.java.ProfileConfig
import dev.eloa.launcher.feature.java.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class RepositoryIntegrationTest : HiltTestBase() {
    @Inject lateinit var bedrock: BedrockRepository
    @Inject lateinit var bedrockDao: BedrockDao
    @Inject lateinit var addons: AddonRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var content: ContentRepository
    @Inject lateinit var files: FileManagerRepository
    @Inject lateinit var modpacks: ModpackRepository
    @Inject lateinit var diagnostics: DiagnosticsEngine
    @Inject lateinit var storage: LauncherStorage

    private val bp = "11111111-1111-1111-1111-111111111111"
    private val bpm = "22222222-2222-2222-2222-222222222222"
    private val rp = "33333333-3333-3333-3333-333333333333"
    private val rpm = "44444444-4444-4444-4444-444444444444"

    @Test fun `bedrock import, duplicates, verify, corruption, delete`() = runBlocking {
        val base = fileUri("base.apk", fixtureApk("base.apk"))
        val split = fileUri("split.apk", fixtureApk("split-arm64.apk"))
        val report = bedrock.import(listOf(base, split)) { _, _ -> }
        assertEquals(report.failures.toString(), 1, report.imported.size)
        val v = bedrock.versions.first().single()
        assertEquals(1234L, v.versionCode); assertEquals("arm64-v8a", v.abis); assertTrue(v.signatureValid)
        assertEquals(2, bedrock.files(v).size)
        assertTrue(File(base.path!!).exists()) // original untouched
        // duplicate
        val again = bedrock.import(listOf(fileUri("base2.apk", fixtureApk("base.apk")), fileUri("s2.apk", fixtureApk("split-arm64.apk")))) { _, _ -> }
        assertEquals(1, again.duplicates.size)
        // corrupted file
        val bad = bedrock.import(listOf(fileUri("broken.apk", "garbage".toByteArray()))) { _, _ -> }
        assertEquals(1, bad.failures.size)
        // split-only rejected
        assertEquals(1, bedrock.import(listOf(fileUri("only.apk", fixtureApk("split-arm64-otherkey.apk")))) { _, _ -> }.failures.size)
        assertTrue(bedrock.verify(v.id).first)
        bedrock.files(v).first().writeText("tampered")
        assertFalse(bedrock.verify(v.id).first)
        assertFalse(bedrockDao.byId(v.id)!!.integrityOk)
        val exported = File(context.cacheDir, "meta.json")
        bedrock.exportMetadata(android.net.Uri.fromFile(exported))
        assertTrue(exported.readText().contains("com.mojang.minecraftpe"))
        bedrock.rename(v.id, "My MC")
        assertEquals("My MC", bedrockDao.byId(v.id)!!.displayName)
        bedrock.delete(v.id, deleteFiles = true)
        assertTrue(bedrock.versions.first().isEmpty())
        assertFalse(File(v.storageDir).exists())
        assertTrue(bedrock.history.first().size >= 4)
        // install planning against a non-installed package
        bedrock.import(listOf(fileUri("b3.apk", fixtureApk("base.apk")), fileUri("s3.apk", fixtureApk("split-arm64.apk")))) { _, _ -> }
        val id = bedrock.versions.first().single().id
        // ARM32-only device: arm64 build must be refused with a clear reason
        org.robolectric.shadows.ShadowBuild.setSupportedAbis(arrayOf("armeabi-v7a", "armeabi"))
        val arm32 = bedrock.planInstall(id)!!
        assertFalse(arm32.canProceed); assertTrue(arm32.explanation.contains("arm64-v8a"))
        // ARM64 device: fresh install allowed through the system installer
        org.robolectric.shadows.ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a"))
        val arm64 = bedrock.planInstall(id)!!
        assertTrue(arm64.explanation, arm64.canProceed)
    }

    @Test fun `addon import, conflicts, toggling and deletion`() = runBlocking {
        val rpPack = zip(mapOf("manifest.json" to manifest("RP", rp, rpm, "resources")))
        val bpPack = zip(mapOf("manifest.json" to manifest("BP", bp, bpm, deps = """{"uuid":"$rp","version":[1,0,0]}""")))
        val r1 = addons.import(listOf(fileUri("bp.mcpack", bpPack))) { _, _ -> }
        assertTrue(r1.single().ok)
        assertTrue(r1.single().findings.any { it.code == "MISSING_DEP" })
        val r2 = addons.import(listOf(fileUri("rp.mcpack", rpPack), fileUri("dup.mcpack", bpPack), fileUri("bad.mcpack", "x".toByteArray()), fileUri("virus.exe", ByteArray(3)))) { _, _ -> }
        assertEquals(listOf(true, false, false, false), r2.map { it.ok })
        val all = addons.addons.first()
        assertEquals(2, all.size)
        val bpEntity = all.first { it.name == "BP" }
        assertTrue(addons.conflicts(bpEntity.id).none { it.code == "MISSING_DEP" }) // satisfied now
        assertEquals(listOf("manifest.json"), addons.contents(bpEntity.id).map { it.first })
        addons.setEnabled(bpEntity.id, false)
        assertFalse(addons.addons.first().first { it.id == bpEntity.id }.enabled)
        val intent = addons.openInMinecraftIntent(bpEntity.id)
        assertEquals("com.mojang.minecraftpe", intent.`package`)
        val backup = File(context.cacheDir, "b.zip"); assertEquals(2, addons.backup(android.net.Uri.fromFile(backup)))
        assertTrue(backup.length() > 0)
        addons.delete(bpEntity.id)
        assertEquals(1, addons.addons.first().size)
    }

    @Test fun `profiles create clone export import delete and stress`() = runBlocking {
        val p = profiles.create(ProfileConfig(name = "Fabric Performance", minecraftVersion = "1.20.1", loader = "FABRIC"))
        val same = profiles.create(ProfileConfig(name = "Fabric Performance", minecraftVersion = "1.20.1", loader = "FABRIC"))
        assertTrue(p.gameDirName != same.gameDirName)
        File(storage.instance(p.gameDirName).mods, "x.jar").writeBytes(fabricMod("x"))
        val clone = profiles.clone(p.id, withFiles = true)
        assertTrue(File(storage.instance(clone.gameDirName).mods, "x.jar").isFile)
        val cfgFile = File(context.cacheDir, "p.json")
        profiles.exportConfig(p.id, android.net.Uri.fromFile(cfgFile))
        val imported = profiles.importConfig(android.net.Uri.fromFile(cfgFile))
        assertEquals("FABRIC", imported.loader)
        var bad = false
        try { profiles.importConfig(fileUri("bad.json", "{oops".toByteArray())) } catch (_: Exception) { bad = true }
        assertTrue(bad)
        var invalid = false
        try { profiles.create(ProfileConfig(name = " ", minecraftVersion = "1.20.1", loader = "VANILLA")) } catch (_: IllegalArgumentException) { invalid = true }
        assertTrue(invalid)
        profiles.delete(clone.id, deleteFiles = true)
        assertFalse(storage.instance(clone.gameDirName).gameDir.exists())
        repeat(200) { profiles.create(ProfileConfig(name = "Stress $it", minecraftVersion = "1.20.1", loader = "VANILLA")) }
        assertEquals(203, profiles.all().size)
    }

    @Test fun `mods and content management through repository`() = runBlocking {
        val p = profiles.create(ProfileConfig(name = "Mods", minecraftVersion = "1.20.1", loader = ModLoaderType.FABRIC.name, loaderVersion = "0.15.11"))
        content.importMod(p, fileUri("api.jar", fabricMod("fabric-api")))
        content.importMod(p, fileUri("needs.jar", fabricMod("needs", "\"missing-lib\":\"*\"")))
        val mods = content.mods(p)
        assertEquals(2, mods.size)
        assertTrue(content.checkMods(p, mods).any { it.code == "MISSING_DEP" && it.severity == IssueSeverity.ERROR })
        content.modManager(p).setEnabled("needs.jar", false)
        assertTrue(content.checkMods(p, content.mods(p)).none { it.severity == IssueSeverity.ERROR })
        // resource pack: valid + invalid
        content.import(p, JavaContentType.RESOURCE_PACKS, fileUri("pack.zip", zip(mapOf("pack.mcmeta" to """{"pack":{"pack_format":15,"description":"d"}}""".toByteArray()))))
        var rejected = false
        try { content.import(p, JavaContentType.RESOURCE_PACKS, fileUri("nopack.zip", zip(mapOf("a" to ByteArray(1))))) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        val rps = content.list(p, JavaContentType.RESOURCE_PACKS)
        assertEquals(dev.eloa.launcher.domain.java.content.PackFormats.Compat.COMPATIBLE, rps.single().compat)
        content.setEnabled(p, JavaContentType.RESOURCE_PACKS, "pack.zip", false)
        assertFalse(content.list(p, JavaContentType.RESOURCE_PACKS).single().enabled)
        // world + datapack
        val world = content.import(p, JavaContentType.WORLDS, fileUri("w.zip", zip(mapOf("My World/level.dat" to ByteArray(4), "My World/region/r.mca" to ByteArray(4)))))
        assertTrue(File(storage.instance(p.gameDirName).saves, "$world/level.dat").isFile)
        content.import(p, JavaContentType.DATAPACKS, fileUri("dp.zip", zip(mapOf("pack.mcmeta" to "{}".toByteArray(), "data/x/a.json" to "{}".toByteArray()))), world)
        assertEquals(1, content.list(p, JavaContentType.DATAPACKS, world).size)
        // config edit with backup
        File(storage.instance(p.gameDirName).config, "a.toml").writeText("a=1")
        content.writeConfig(p, "a.toml", "a=2")
        assertEquals("a=2", content.readConfig(p, "a.toml"))
        assertTrue(File(storage.instance(p.gameDirName).config, ".a.toml.bak").isFile)
        var traversal = false
        try { content.delete(p, JavaContentType.CONFIGS, "../mods/api.jar") } catch (_: Exception) { traversal = true }
        assertTrue(traversal)
        // shader loader detection
        assertFalse(content.shaderLoaderPresent(content.mods(p)))
    }

    @Test fun `eloapack export and import round trip`() = runBlocking {
        val p = profiles.create(ProfileConfig(name = "Pack", description = "desc", minecraftVersion = "1.20.1", loader = "QUILT"))
        File(storage.instance(p.gameDirName).mods, "q.jar").writeBytes(fabricMod("q"))
        File(storage.instance(p.gameDirName).saves, "w/level.dat").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2)) }
        val out = File(context.cacheDir, "x.eloapack")
        assertEquals(1, modpacks.export(p, android.net.Uri.fromFile(out), includeWorlds = false))
        val (tmp, preview) = modpacks.preview(android.net.Uri.fromFile(out))
        assertEquals("Pack", preview.name); assertEquals("Eloa", preview.format); assertEquals(1, preview.fileCount)
        val newId = modpacks.install(tmp, preview)
        val np = profiles.get(newId)!!
        assertTrue(File(storage.instance(np.gameDirName).mods, "q.jar").isFile)
        assertFalse(File(storage.instance(np.gameDirName).saves, "w").exists())
    }

    @Test fun `file manager operations stay inside roots`() = runBlocking {
        val root = FileRoot.EXPORTS
        files.createFolder(root, "", "a")
        files.import(root, "a", fileUri("note.txt", "hello".toByteArray()))
        assertEquals(listOf("note.txt"), files.list(root, "a", FileSort.NAME, true).map { it.name })
        files.rename(root, "a/note.txt", "n.txt")
        files.copyOrMove(root, "a/n.txt", FileRoot.BACKUPS, "", move = false)
        files.copyOrMove(root, "a/n.txt", root, "", move = true)
        assertEquals(setOf("a", "n.txt"), files.list(root, "", FileSort.SIZE, true).map { it.name }.toSet())
        assertEquals(1, files.search(root, "n.t").size)
        assertTrue(files.sha256(root, "n.txt").length == 64)
        assertTrue(files.backup(root, "a").endsWith(".zip"))
        var escaped = false
        try { files.delete(root, "../minecraft") } catch (_: Exception) { escaped = true }
        assertTrue(escaped)
        var rootDelete = false
        try { files.delete(root, "") } catch (_: Exception) { rootDelete = true }
        assertTrue(rootDelete)
        var badName = false
        try { files.createFolder(root, "", "x/y") } catch (_: IllegalArgumentException) { badName = true }
        assertTrue(badName)
        files.delete(root, "a")
        assertFalse(files.list(root, "", FileSort.DATE, false).any { it.name == "a" })
    }

    @Test fun `diagnostics report and safe fixes`() = runBlocking {
        val p = profiles.create(ProfileConfig(name = "Broken", minecraftVersion = "1.20.1", loader = "FABRIC", maxMemoryMb = 30_000, minMemoryMb = 512))
        File(storage.instance(p.gameDirName).mods, "bad.jar").writeBytes(fabricMod("bad", "\"nothing\":\"*\""))
        File(storage.importTemp, "leftover.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        val report = diagnostics.run()
        assertTrue(report.checksRun >= 10)
        val ids = report.issues.map { it.id }
        assertTrue(ids.toString(), ids.any { it.startsWith("mods-") })
        assertTrue(ids.toString(), ids.any { it.startsWith("mem-") })
        assertTrue(ids.contains("auth-client"))
        val memFix = report.issues.first { it.id.startsWith("mem-") }.fix!!
        assertTrue(memFix.requiresConfirmation)
        diagnostics.applyFix(memFix.id)
        assertTrue(profiles.get(p.id)!!.maxMemoryMb < 30_000)
        val modFix = report.issues.first { it.id.startsWith("mods-") }.fix!!
        assertTrue(modFix.requiresConfirmation)
        diagnostics.applyFix(modFix.id)
        assertTrue(content.mods(p).none { it.enabled })
        assertNotNull(diagnostics.applyFix("cleanup-all"))
    }
}
