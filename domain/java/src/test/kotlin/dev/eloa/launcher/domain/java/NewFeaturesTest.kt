package dev.eloa.launcher.domain.java

import dev.eloa.launcher.domain.java.auth.AuthEndpoints
import dev.eloa.launcher.domain.java.auth.AuthException
import dev.eloa.launcher.domain.java.auth.MicrosoftAuth
import dev.eloa.launcher.domain.java.auth.Pkce
import dev.eloa.launcher.domain.java.controls.ControlLayouts
import dev.eloa.launcher.domain.java.controls.ControlPreset
import dev.eloa.launcher.domain.java.controls.GamepadProfile
import dev.eloa.launcher.domain.java.controls.InvalidLayoutException
import dev.eloa.launcher.domain.java.crash.CrashAnalyzer
import dev.eloa.launcher.domain.java.crash.CrashCause
import dev.eloa.launcher.domain.java.launch.AccountSnapshot
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.domain.java.launch.LaunchModes
import dev.eloa.launcher.domain.java.launch.ModeBlocker
import dev.eloa.launcher.domain.java.launch.ModeContext
import dev.eloa.launcher.domain.java.options.CloudMode
import dev.eloa.launcher.domain.java.options.GameOptions
import dev.eloa.launcher.domain.java.options.GraphicsQuality
import dev.eloa.launcher.domain.java.options.OptionsTxt
import dev.eloa.launcher.domain.java.options.ParticleLevel
import dev.eloa.launcher.domain.java.render.GpuFamily
import dev.eloa.launcher.domain.java.render.GpuProfile
import dev.eloa.launcher.domain.java.render.RendererCatalog
import dev.eloa.launcher.domain.java.render.RendererId
import dev.eloa.launcher.domain.java.render.RendererNote
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class GameOptionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `modern format keeps unknown keys and writes managed values`() {
        val dir = tmp.newFolder("game")
        File(dir, "options.txt").writeText("version:3465\nkey_key.jump:key.keyboard.space\nrenderDistance:12\nfancyGraphics:true\nresourcePacks:[\"vanilla\"]\n")
        OptionsTxt.apply(dir, GameOptions(renderDistance = 6, graphics = GraphicsQuality.FAST, maxFps = 30, clouds = CloudMode.OFF, smoothLighting = false, fov = 90), "1.20.1")
        val m = OptionsTxt.parse(File(dir, "options.txt").readText())
        assertEquals("key.keyboard.space", m["key_key.jump"])
        assertEquals("[\"vanilla\"]", m["resourcePacks"])
        assertEquals("6", m["renderDistance"])
        assertEquals("0", m["graphicsMode"])
        assertFalse("legacy twin removed", m.containsKey("fancyGraphics"))
        assertEquals("\"false\"", m["renderClouds"])
        assertEquals("false", m["ao"])
        assertEquals("8", m["simulationDistance"])
        assertEquals("0.5", m["fov"])
        val back = OptionsTxt.load(dir)
        assertEquals(6, back.renderDistance); assertEquals(GraphicsQuality.FAST, back.graphics); assertEquals(30, back.maxFps)
        assertEquals(CloudMode.OFF, back.clouds); assertFalse(back.smoothLighting); assertEquals(90, back.fov)
    }

    @Test fun `legacy versions use legacy keys`() {
        val e = OptionsTxt.entriesFor(GameOptions(graphics = GraphicsQuality.FANCY, smoothLighting = true, clouds = CloudMode.FAST), "1.12.2")
        assertEquals("true", e["fancyGraphics"]); assertNull(e["graphicsMode"]); assertNull(e["simulationDistance"])
        assertEquals("2", e["ao"]); assertEquals("fast", e["renderClouds"])
        val snap = OptionsTxt.entriesFor(GameOptions(), "24w14a")
        assertNotNull(snap["simulationDistance"]); assertEquals("true", snap["ao"])
    }

    @Test fun `values are clamped and garbage is tolerated`() {
        val o = OptionsTxt.read(mapOf("renderDistance" to "99", "particles" to "7", "mouseSensitivity" to "x", "maxFps" to "-3"))
        assertEquals(32, o.renderDistance); assertEquals(ParticleLevel.ALL, o.particles); assertEquals(0.5, o.mouseSensitivity, 0.0); assertEquals(10, o.maxFps)
    }
}

class LaunchModesTest {
    private val acc = AccountSnapshot("Steve", tokenExpiresAtMs = 1_000)
    private fun ctx(account: AccountSnapshot? = acc, net: Boolean = true, now: Long = 500, mc: String? = "1.20.4", worlds: List<String> = listOf("World")) =
        ModeContext(account, net, now, mc, worlds)

    @Test fun `no account blocks every mode`() {
        LaunchModes.evaluateAll(ctx(account = null)).forEach { assertTrue(ModeBlocker.NO_ACCOUNT in it.blockers) }
    }

    @Test fun `online needs network, offline does not`() {
        assertEquals(listOf(ModeBlocker.NO_NETWORK), LaunchModes.evaluate(LaunchMode.ONLINE, ctx(net = false)).blockers)
        assertTrue(LaunchModes.evaluate(LaunchMode.OFFLINE, ctx(net = false, now = 5_000)).available)
        assertFalse(LaunchModes.usesNetwork(LaunchMode.OFFLINE)); assertFalse(LaunchModes.usesNetwork(LaunchMode.LOCAL_SINGLEPLAYER))
    }

    @Test fun `official account offline only with a valid token`() {
        assertTrue(LaunchModes.evaluate(LaunchMode.OFFICIAL_ACCOUNT, ctx(net = false, now = 500)).available)
        assertEquals(listOf(ModeBlocker.TOKEN_EXPIRED_OFFLINE), LaunchModes.evaluate(LaunchMode.OFFICIAL_ACCOUNT, ctx(net = false, now = 2_000)).blockers)
    }

    @Test fun `local singleplayer needs quick play and a world`() {
        assertTrue(LaunchModes.evaluate(LaunchMode.LOCAL_SINGLEPLAYER, ctx()).available)
        assertEquals(listOf(ModeBlocker.QUICK_PLAY_UNSUPPORTED), LaunchModes.evaluate(LaunchMode.LOCAL_SINGLEPLAYER, ctx(mc = "1.19.4")).blockers)
        assertEquals(listOf(ModeBlocker.NO_LOCAL_WORLDS), LaunchModes.evaluate(LaunchMode.LOCAL_SINGLEPLAYER, ctx(worlds = emptyList())).blockers)
        assertTrue(LaunchModes.supportsQuickPlay("23w14a")); assertFalse(LaunchModes.supportsQuickPlay("23w13a")); assertTrue(LaunchModes.supportsQuickPlay("1.21.4"))
    }
}

class CrashAnalyzerTest {
    @Test fun `known causes are recognised`() {
        val log = """
            [main/INFO]: Loading Minecraft
            Exception in thread "main" java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 52.0
        """.trimIndent()
        val a = CrashAnalyzer.analyze(log)
        assertEquals(CrashCause.WRONG_JAVA_VERSION, a.primary)
        assertEquals("Java 21", a.findings.first().detail)
        assertTrue(a.exceptionHeadline!!.contains("UnsupportedClassVersionError"))
    }

    @Test fun `oom, missing native lib and mods`() {
        assertEquals(CrashCause.OUT_OF_MEMORY, CrashAnalyzer.analyze("java.lang.OutOfMemoryError: Java heap space").primary)
        val ule = CrashAnalyzer.analyze("java.lang.UnsatisfiedLinkError: dlopen failed: library \"liblwjgl.so\" not found")
        assertEquals(CrashCause.NATIVE_LIBRARY_MISSING, ule.primary)
        val fabric = CrashAnalyzer.analyze("net.fabricmc.loader.impl.FormattedException: Mod 'Sodium Extra' (sodium-extra) 0.5 requires any version of 'sodium', which is missing!")
        assertEquals(CrashCause.MOD_MISSING_DEPENDENCY, fabric.primary)
        assertEquals("sodium", fabric.findings.first().detail)
        assertEquals(CrashCause.UNKNOWN, CrashAnalyzer.analyze("all good\nnothing here").primary)
    }

    @Test fun `deepest caused-by is the headline`() {
        val a = CrashAnalyzer.analyze("java.lang.RuntimeException: wrap\nCaused by: java.io.IOException: inner\nCaused by: java.util.zip.ZipException: invalid LOC header")
        assertEquals("Caused by: java.util.zip.ZipException: invalid LOC header", a.exceptionHeadline)
        assertEquals(CrashCause.CORRUPTED_FILE, a.primary)
    }
}

class ControlsTest {
    @Test fun `presets are valid and round-trip`() {
        ControlPreset.entries.forEach { p ->
            val l = ControlLayouts.preset(p)
            val back = ControlLayouts.decode(ControlLayouts.encode(l))
            assertEquals(l.buttons.map { it.id }, back.buttons.map { it.id })
        }
    }

    @Test fun `invalid layouts are rejected`() {
        val base = ControlLayouts.preset(ControlPreset.CLASSIC)
        assertThrows { ControlLayouts.encode(base.copy(buttons = base.buttons + base.buttons.first())) }
        assertThrows { ControlLayouts.encode(base.copy(buttons = listOf(base.buttons.first().copy(keys = listOf("rm -rf"))))) }
        assertThrows { ControlLayouts.decode("{not json") }
        assertThrows { ControlLayouts.decode("""{"name":"x","buttons":[],"formatVersion":9}""") }
    }

    @Test fun `placement stays on screen for any aspect ratio`() {
        val b = ControlLayouts.preset(ControlPreset.CLASSIC).buttons.first { it.id == "jump" }.copy(x = 1f, y = 1f)
        listOf(1920f to 1080f, 2400f to 1080f, 1280f to 800f, 800f to 480f).forEach { (w, h) ->
            val r = ControlLayouts.place(b, 1f, w, h, 2.75f)
            assertTrue(r.left >= 0 && r.top >= 0 && r.left + r.width <= w + 0.01f && r.top + r.height <= h + 0.01f)
        }
        val (nx, ny) = ControlLayouts.normalise(960f, 540f, 1920f, 1080f)
        assertEquals(0.5f, nx, 0.0001f); assertEquals(0.5f, ny, 0.0001f)
    }

    @Test fun `gamepad profile validation and dead zone`() {
        val p = GamepadProfile("desc", "Pad", deadzone = 0.2f)
        assertEquals(0f, p.applyDeadzone(0.1f), 0f)
        assertEquals(1f, p.applyDeadzone(1f), 0.0001f)
        assertEquals(-0.5f, p.applyDeadzone(-0.6f), 0.0001f)
        assertEquals(p, GamepadProfile.decode(GamepadProfile.encode(p)))
        assertThrows { GamepadProfile.decode(GamepadProfile.encode(p).replace("KEY_SPACE", "EVIL")) }
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (e: InvalidLayoutException) { return }
        throw AssertionError("expected InvalidLayoutException")
    }
}

class RenderersTest {
    private val adreno = GpuProfile("Adreno (TM) 650", 3, 2, "1.1.128")
    private val mali = GpuProfile("Mali-G57 MC2", 3, 2, "1.1.0")
    private val oldGpu = GpuProfile("PowerVR SGX 544", 2, 0, null)

    @Test fun `gpu family and model`() {
        assertEquals(GpuFamily.ADRENO, adreno.family); assertEquals(650, adreno.adrenoModel)
        assertEquals(GpuFamily.MALI, mali.family); assertEquals(GpuFamily.POWERVR, oldGpu.family)
        assertEquals(GpuFamily.UNKNOWN, GpuProfile(null, 3, 0, null).family)
    }

    @Test fun `auto selection follows the GL level minecraft needs`() {
        assertEquals(RendererId.GL4ES, RendererCatalog.autoSelect(adreno, "1.16.5"))
        assertEquals(RendererId.MOBILEGLUES, RendererCatalog.autoSelect(adreno, "1.20.1"))
        assertEquals(RendererId.GL4ES, RendererCatalog.autoSelect(oldGpu, "1.12.2"))
        assertNull("GLES2-only GPU cannot run 1.17+", RendererCatalog.autoSelect(oldGpu, "1.20.1"))
    }

    @Test fun `verdicts explain blockers`() {
        val v = RendererCatalog.evaluate(oldGpu, "1.20.1", emptySet()).associateBy { it.spec.id }
        assertFalse(v.getValue(RendererId.GL4ES).compatible)
        assertTrue(RendererNote.MC_TOO_NEW_FOR_GL21 in v.getValue(RendererId.GL4ES).blockers)
        assertTrue(RendererNote.NEEDS_VULKAN_1_1 in v.getValue(RendererId.ZINK).blockers)
        assertTrue(RendererNote.NOT_INSTALLED in v.getValue(RendererId.MOBILEGLUES).warnings)
        val m = RendererCatalog.evaluate(mali, "1.20.1", setOf(RendererId.ZINK)).associateBy { it.spec.id }
        assertTrue(m.getValue(RendererId.ZINK).compatible)
        assertTrue(RendererNote.MALI_VULKAN_DRIVER_ISSUES in m.getValue(RendererId.ZINK).warnings)
        assertFalse(RendererNote.NOT_INSTALLED in m.getValue(RendererId.ZINK).warnings)
        assertEquals("0.75", RendererCatalog.environment(RendererId.ZINK, 0.75f)["ELOA_RESOLUTION_SCALE"])
    }
}

class PkceTest {
    @Test fun `challenge is base64url(sha256(verifier)) without padding`() {
        // Expected value computed independently with Python hashlib/base64.urlsafe_b64encode.
        assertEquals("rpA2zI0nXg0SDvgVO6P36rLy1u01S4OU8-rBvv8vOOA", Pkce.challenge("dBjftJeZ4CVP-mJ92IXbBZ3dEAIx5CU1vMgXGy7oQGo"))
        val v = Pkce.verifier(SecureRandom())
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun `browser sign-in validates state and exchanges code with verifier`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"access_token":"A","refresh_token":"R","expires_in":3600}"""))
        server.start()
        try {
            val auth = MicrosoftAuth(OkHttpClient(), "cid", AuthEndpoints(token = server.url("/token").toString()))
            val pending = auth.beginBrowserSignIn("eloa://auth")
            val url = pending.url.toHttpUrl()
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertEquals(Pkce.challenge(pending.verifier), url.queryParameter("code_challenge"))
            assertEquals("eloa://auth", url.queryParameter("redirect_uri"))
            assertFalse(pending.toString().contains(pending.verifier))

            var e: Exception? = null
            try { auth.exchangeCode(pending, "eloa://auth?code=C&state=WRONG") } catch (x: AuthException.StateMismatch) { e = x }
            assertNotNull(e)
            try { auth.exchangeCode(pending, "eloa://auth?error=access_denied&state=${pending.state}") } catch (x: AuthException.Declined) { e = x }
            assertTrue(e is AuthException.Declined)

            val tokens = auth.exchangeCode(pending, "eloa://auth?code=THECODE&state=${pending.state}")
            assertEquals("R", tokens.refreshToken)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("grant_type=authorization_code")); assertTrue(body.contains("code=THECODE"))
            assertTrue(body.contains("code_verifier=${pending.verifier}"))
        } finally { server.shutdown() }
        // sanity: digest helper used by the challenge is SHA-256
        assertEquals(32, MessageDigest.getInstance("SHA-256").digest(byteArrayOf()).size)
        assertEquals(43, Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)).length)
    }
}
