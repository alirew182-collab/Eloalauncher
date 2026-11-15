package dev.eloa.launcher.domain.java

import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.domain.java.download.DownloadConfig
import dev.eloa.launcher.domain.java.download.DownloadManager
import dev.eloa.launcher.domain.java.install.GameDirectories
import dev.eloa.launcher.domain.java.install.InstallStage
import dev.eloa.launcher.domain.java.install.VersionInstaller
import dev.eloa.launcher.domain.java.launch.LaunchIdentity
import dev.eloa.launcher.domain.java.launch.LaunchPlanBuilder
import dev.eloa.launcher.domain.java.launch.LaunchPlanException
import dev.eloa.launcher.domain.java.launch.LaunchPreflight
import dev.eloa.launcher.domain.java.launch.LaunchSettings
import dev.eloa.launcher.domain.java.loader.FabricLikeProvider
import dev.eloa.launcher.domain.java.loader.ForgeLikeProvider
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.meta.RuleEnvironment
import dev.eloa.launcher.domain.java.meta.VersionJson
import dev.eloa.launcher.domain.java.meta.VersionManifest
import dev.eloa.launcher.domain.java.meta.VersionResolver
import dev.eloa.launcher.domain.java.net.MetaClient
import dev.eloa.launcher.domain.java.net.SourcePolicy
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInspector
import dev.eloa.launcher.domain.java.runtime.RuntimeValidationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end install of a (synthetic) Minecraft version through the real policy
 * (official HTTPS hosts only); an interceptor routes those hosts to MockWebServer.
 */
class InstallAndLaunchTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val files = HashMap<String, ByteArray>()
    private val env = RuleEnvironment.forAbi("arm64-v8a")
    private val identity = LaunchIdentity("Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5", "SECRET_TOKEN_VALUE", "123", "cid")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = request.getHeader("X-Orig-Host") + request.path
                return files[key]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun client() = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val orig = chain.request()
        val url = orig.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
        chain.proceed(orig.newBuilder().url(url).header("X-Orig-Host", orig.url.host).build())
    }).build()

    private fun host(url: String, body: ByteArray) { val u = okhttp3.HttpUrl.Builder().scheme("https").host("x").build().resolve(url)!!; files[u.host + u.encodedPath] = body }
    private fun sha1(b: ByteArray) = Hashing.digest(b, HashAlgorithm.SHA1)

    private fun buildSyntheticVersion(): Pair<VersionManifest.Entry, VersionJson> {
        val lib = "library-bytes".toByteArray()
        val client = "client-jar".toByteArray()
        val asset1 = "sound".toByteArray(); val asset2 = "lang".toByteArray()
        val index = """{"objects":{"minecraft/sounds/a.ogg":{"hash":"${sha1(asset1)}","size":${asset1.size}},"minecraft/lang/en.json":{"hash":"${sha1(asset2)}","size":${asset2.size}}}}""".toByteArray()
        host("https://resources.download.minecraft.net/${sha1(asset1).take(2)}/${sha1(asset1)}", asset1)
        host("https://resources.download.minecraft.net/${sha1(asset2).take(2)}/${sha1(asset2)}", asset2)
        host("https://piston-meta.mojang.com/idx/9.json", index)
        host("https://libraries.minecraft.net/com/example/lib/1.0/lib-1.0.jar", lib)
        host("https://piston-data.mojang.com/client.jar", client)
        val json = """{"id":"9.9","type":"release","mainClass":"net.minecraft.client.main.Main","javaVersion":{"majorVersion":21},
          "assetIndex":{"id":"9","sha1":"${sha1(index)}","size":${index.size},"url":"https://piston-meta.mojang.com/idx/9.json"},
          "downloads":{"client":{"sha1":"${sha1(client)}","size":${client.size},"url":"https://piston-data.mojang.com/client.jar"}},
          "libraries":[{"name":"com.example:lib:1.0","downloads":{"artifact":{"path":"com/example/lib/1.0/lib-1.0.jar","sha1":"${sha1(lib)}","size":${lib.size},"url":"https://libraries.minecraft.net/com/example/lib/1.0/lib-1.0.jar"}}}],
          "arguments":{"game":["--username","${'$'}{auth_player_name}","--accessToken","${'$'}{auth_access_token}","--gameDir","${'$'}{game_directory}"],"jvm":["-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"]}}""".toByteArray()
        host("https://piston-meta.mojang.com/v/9.9.json", json)
        return VersionManifest.Entry("9.9", "release", "https://piston-meta.mojang.com/v/9.9.json", sha1 = sha1(json)) to VersionJson.parse(String(json))
    }

    private fun installer(dirs: GameDirectories): VersionInstaller {
        val c = client()
        return VersionInstaller(MetaClient(c), DownloadManager(c, SourcePolicy.OFFICIAL, DownloadConfig(retryBaseDelayMs = 5, reserveBytes = 0)), dirs, env)
    }

    @Test fun `installs vanilla version, verifies integrity, detects corruption`() = runBlocking {
        val (entry, _) = buildSyntheticVersion()
        val dirs = GameDirectories(tmp.root)
        val stages = mutableListOf<InstallStage>()
        val inst = installer(dirs)
        val v = inst.installVanilla(entry, onStage = { stages += it })
        assertEquals(InstallStage.DONE, stages.last())
        assertTrue(dirs.clientJar("9.9").isFile)
        assertEquals(listOf("9.9"), dirs.installedVersionIds())
        assertTrue(inst.verify(v, "9.9").ok)
        dirs.clientJar("9.9").writeText("tampered!!")
        val report = inst.verify(v, "9.9")
        assertFalse(report.ok); assertEquals(1, report.corrupted.size)
        // repair = reinstall
        inst.installResolved(v, "9.9")
        assertTrue(inst.verify(v, "9.9").ok)
    }

    @Test fun `version json checksum mismatch is refused`() = runBlocking {
        val (entry, _) = buildSyntheticVersion()
        var failed = false
        try { installer(GameDirectories(tmp.root)).fetchVersionJson(entry.copy(sha1 = "1".repeat(40))) } catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
    }

    @Test fun `launch plan substitutes variables and redacts token`() = runBlocking {
        val (entry, _) = buildSyntheticVersion()
        val dirs = GameDirectories(tmp.root)
        val v = installer(dirs).installVanilla(entry)
        val gameDir = File(tmp.root, "instances/p1").apply { mkdirs() }
        val plan = LaunchPlanBuilder.build(v, dirs, gameDir, identity, LaunchSettings(512, 2048, 1280, 720), env)
        assertEquals("net.minecraft.client.main.Main", plan.mainClass)
        assertTrue(plan.jvmArgs.containsAll(listOf("-Xms512M", "-Xmx2048M")))
        assertTrue(plan.gameArgs.containsAll(listOf("--username", "Steve", "--width", "1280")))
        assertTrue(plan.gameArgs.contains(gameDir.absolutePath))
        assertTrue(plan.classpath.any { it.name == "9.9.jar" } && plan.classpath.any { it.name == "lib-1.0.jar" })
        assertFalse(plan.redactedCommandLine().contains("SECRET_TOKEN_VALUE"))
        assertFalse(identity.toString().contains("SECRET"))
        val pre = LaunchPreflight.check(plan, null, 8000, 0, backendAvailable = false)
        assertTrue(pre.any { it.code == "NO_RUNTIME" }); assertTrue(pre.any { it.code == "NO_BACKEND" }); assertTrue(pre.none { it.code == "MISSING_FILES" })
    }

    @Test fun `launch plan for 1_20_1 json with legacy and fabric merge`() = runBlocking {
        val vanilla = VersionJson.parse(TestUtil.resource("1.20.1.json"))
        val fabric = VersionResolver.resolve(VersionJson.parse(TestUtil.resource("fabric-profile.json"))) { vanilla }
        val dirs = GameDirectories(tmp.root)
        val plan = LaunchPlanBuilder.build(fabric, dirs, tmp.newFolder("g"), identity, LaunchSettings(512, 1024), env, clientJarId = "1.20.1")
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", plan.mainClass)
        assertFalse(plan.gameArgs.contains("--demo"))
        assertFalse(plan.gameArgs.contains("--quickPlayPath"))
        assertFalse(plan.jvmArgs.contains("-XstartOnFirstThread"))
        assertTrue(plan.jvmArgs.any { it.startsWith("-Dlog4j.configurationFile=") })
        assertTrue(plan.jvmArgs.contains("-DFabricMcEmu= net.minecraft.client.main.Main "))
        val pre = LaunchPreflight.check(plan, null, 300, 0, false)
        assertTrue(pre.any { it.code == "MISSING_FILES" }); assertTrue(pre.any { it.code == "LOW_RAM" })
        val legacy = VersionJson(id = "1.7.10", mainClass = "net.minecraft.client.main.Main", minecraftArguments = "--username \${auth_player_name} --session \${auth_session} --assetsDir \${game_assets}", assets = "legacy")
        val lp = LaunchPlanBuilder.build(legacy, dirs, tmp.newFolder("g2"), identity, LaunchSettings(256, 512), env)
        assertTrue(lp.gameArgs.contains("token:SECRET_TOKEN_VALUE:069a79f444e94726a5befca90e38aaf5"))
        assertTrue(lp.jvmArgs.contains("-cp"))
        assertFalse(lp.redactedCommandLine().contains("SECRET_TOKEN_VALUE"))
    }

    @Test fun `unsafe jvm args and memory are rejected`() {
        val v = VersionJson(id = "x", mainClass = "M")
        val dirs = GameDirectories(tmp.root)
        var t1 = false; var t2 = false; var t3 = false
        try { LaunchPlanBuilder.build(v, dirs, tmp.root, identity, LaunchSettings(256, 512, extraJvmArgs = listOf("-javaagent:/sdcard/x.jar")), env) } catch (_: LaunchPlanException) { t1 = true }
        try { LaunchPlanBuilder.build(v, dirs, tmp.root, identity, LaunchSettings(1024, 512), env) } catch (_: LaunchPlanException) { t2 = true }
        try { LaunchPlanBuilder.build(VersionJson(id = "y"), dirs, tmp.root, identity, LaunchSettings(256, 512), env) } catch (_: LaunchPlanException) { t3 = true }
        assertTrue(t1 && t2 && t3)
    }

    @Test fun `fabric and quilt providers via meta api`() = runBlocking {
        host("https://meta.fabricmc.net/v2/versions/loader/1.20.1", """[{"loader":{"version":"0.16.0","stable":true}},{"loader":{"version":"0.16.1-beta","stable":false}}]""".toByteArray())
        host("https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.16.0/profile/json", TestUtil.resource("fabric-profile.json").replace("0.15.11", "0.16.0").toByteArray())
        host("https://meta.quiltmc.org/v3/versions/loader/1.20.1", """[{"loader":{"version":"0.26.0"}},{"loader":{"version":"0.26.1-beta.1"}}]""".toByteArray())
        val meta = MetaClient(client())
        val fp = FabricLikeProvider(ModLoaderType.FABRIC, meta)
        val versions = fp.versions("1.20.1")
        assertEquals(2, versions.size); assertTrue(versions[0].stable); assertFalse(versions[1].stable)
        val dirs = GameDirectories(tmp.root)
        val profile = fp.install("1.20.1", "0.16.0", dirs)
        assertTrue(dirs.versionJson(profile.versionId).isFile)
        val q = FabricLikeProvider(ModLoaderType.QUILT, meta).versions("1.20.1")
        assertTrue(q[0].stable); assertFalse(q[1].stable)
    }

    @Test fun `forge and neoforge version lists and installer reading`() = runBlocking {
        val forgeXml = "<metadata><versioning><versions><version>1.20.1-47.1.0</version><version>1.20.1-47.2.0</version><version>1.19.2-43.3.0</version></versions></versioning></metadata>"
        host("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml", forgeXml.toByteArray())
        val neoXml = "<metadata><versioning><versions><version>20.4.237</version><version>21.0.10-beta</version><version>21.1.5</version></versions></versioning></metadata>"
        host("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml", neoXml.toByteArray())
        val meta = MetaClient(client())
        val forge = ForgeLikeProvider(ModLoaderType.FORGE, meta)
        assertEquals(listOf("1.20.1-47.2.0", "1.20.1-47.1.0"), forge.versions("1.20.1").map { it.version })
        val neo = ForgeLikeProvider(ModLoaderType.NEOFORGE, meta)
        assertEquals(listOf("21.0.10-beta"), neo.versions("1.21").map { it.version })
        assertFalse(neo.versions("1.21").single().stable)
        assertEquals("1.20.4", ForgeLikeProvider.neoForgeMinecraftVersion("20.4.237"))
        assertEquals("1.21.1", ForgeLikeProvider.neoForgeMinecraftVersion("21.1.5"))
        assertEquals("26.1", ForgeLikeProvider.neoForgeMinecraftVersion("26.1.0.5-beta"))
        assertEquals("1.20.1", ForgeLikeProvider.neoForgeMinecraftVersion("1.20.1-47.1.100"))

        val installerJar = TestUtil.jar(File(tmp.root, "installer.jar"), mapOf(
            "install_profile.json" to """{"spec":1,"json":"/version.json","libraries":[{"name":"net.minecraftforge:installertools:1.3.0","downloads":{"artifact":{"path":"net/minecraftforge/installertools/1.3.0/installertools-1.3.0.jar","url":"https://maven.minecraftforge.net/x.jar","sha1":"${"a".repeat(40)}","size":1}}}],
               "processors":[{"jar":"a:b:1","sides":["server"]},{"jar":"a:c:1"},{"jar":"a:d:1","sides":["client"]}]}""",
            "version.json" to """{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher","libraries":[{"name":"net.minecraftforge:forge:1.20.1-47.2.0:client","downloads":{"artifact":{"path":"net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-client.jar","url":"","sha1":"${"b".repeat(40)}","size":1}}}]}""",
            "maven/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar" to "universal",
        ))
        val dirs = GameDirectories(tmp.root)
        val lp = forge.readInstaller(installerJar, "1.20.1", dirs)
        assertEquals(2, lp.pendingProcessors) // server-only processor excluded
        assertEquals(2, lp.profile.libraries.size)
        assertTrue(dirs.library("net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar").isFile)
        assertTrue(dirs.versionJson("1.20.1-forge-47.2.0").isFile)
        // generated library (empty url) is not downloadable
        val libs = dev.eloa.launcher.domain.java.install.LibraryPlanner.resolve(lp.profile, dirs, env)
        assertTrue(libs.first { it.library.name.endsWith(":client") }.download == null)
        var wrongMc = false
        try { forge.readInstaller(installerJar, "1.19.2", dirs) } catch (_: java.io.IOException) { wrongMc = true }
        assertTrue(wrongMc)
    }

    private fun fakeRuntime(dir: File, arch: String, machine: Int, version: String = "17.0.9"): File {
        dir.mkdirs()
        File(dir, "release").writeText("JAVA_VERSION=\"$version\"\nOS_ARCH=\"$arch\"\nIMPLEMENTOR=\"Test\"\n")
        val so = File(dir, "lib/server/libjvm.so"); so.parentFile.mkdirs()
        so.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0) + ByteArray(10) + byteArrayOf((machine and 0xFF).toByte(), (machine shr 8).toByte()))
        return dir
    }

    @Test fun `runtime inspection validates arch and version`() {
        val info = JavaRuntimeInspector.inspect(fakeRuntime(File(tmp.root, "jre17"), "aarch64", 183), "arm64-v8a")
        assertEquals(17, info.majorVersion)
        assertEquals(8, JavaRuntimeInspector.majorOf("1.8.0_392"))
        var thrown = false
        try { JavaRuntimeInspector.inspect(fakeRuntime(File(tmp.root, "x64"), "x86_64", 62), "arm64-v8a") } catch (_: RuntimeValidationException) { thrown = true }
        assertTrue(thrown)
        val zip = File(tmp.root, "rt.zip")
        val src = fakeRuntime(File(tmp.root, "zsrc/jre-21"), "aarch64", 183, "21.0.2")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry("jre-21/" + f.relativeTo(src).path)); z.write(f.readBytes()); z.closeEntry()
            }
        }
        val imported = JavaRuntimeInspector.importZip(zip, File(tmp.root, "runtimes/21"), "arm64-v8a")
        assertEquals(21, imported.majorVersion)
        assertTrue(File(tmp.root, "runtimes/21/release").isFile)
    }
}
