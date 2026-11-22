package dev.eloa.launcher.domain.java

import dev.eloa.launcher.domain.java.auth.AuthlibInjectorDownloader
import dev.eloa.launcher.domain.java.auth.ElyAuthException
import dev.eloa.launcher.domain.java.auth.ElyByAuth
import dev.eloa.launcher.domain.java.auth.ElyTokens
import dev.eloa.launcher.domain.java.auth.MinecraftSkinApi
import dev.eloa.launcher.domain.java.net.SourcePolicy
import dev.eloa.launcher.domain.java.skins.Face
import dev.eloa.launcher.domain.java.skins.InvalidSkinException
import dev.eloa.launcher.domain.java.skins.SkinImage
import dev.eloa.launcher.domain.java.skins.SkinModel
import dev.eloa.launcher.domain.java.skins.Skins
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class SkinsTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun `only 64x64 and 64x32 are accepted`() {
        listOf(32 to 32, 64 to 128, 128 to 128, 63 to 64).forEach { (w, h) ->
            assertTrue(runCatching { Skins.normalize(SkinImage(w, h, IntArray(w * h))) }.exceptionOrNull() is InvalidSkinException)
        }
        assertEquals(64, Skins.normalize(SkinImage(64, 32, IntArray(64 * 32))).height)
    }

    @Test fun `legacy skins mirror right limbs to the left`() {
        val old = SkinImage(64, 32, IntArray(64 * 32))
        val rightLegFront = Skins.parts(SkinModel.CLASSIC).first { it.key == "right_leg" }.face(Face.FRONT)
        old[rightLegFront.x, rightLegFront.y] = red // leftmost pixel of the right leg front
        val converted = Skins.convertLegacy(old)
        val leftLegFront = Skins.parts(SkinModel.CLASSIC).first { it.key == "left_leg" }.face(Face.FRONT)
        assertEquals(red, converted[leftLegFront.x + leftLegFront.w - 1, leftLegFront.y])
        assertEquals(red, converted[rightLegFront.x, rightLegFront.y])
    }

    @Test fun `slim detection and blank skins`() {
        val classic = Skins.blank(SkinModel.CLASSIC)
        assertEquals(SkinModel.CLASSIC, Skins.detectModel(classic))
        val slim = Skins.blank(SkinModel.SLIM)
        assertEquals(SkinModel.SLIM, Skins.detectModel(slim))
        assertTrue(Skins.isUsed(SkinModel.CLASSIC, 8, 8)); assertFalse(Skins.isUsed(SkinModel.CLASSIC, 0, 0))
    }

    @Test fun `preview composes head, overlay and limbs`() {
        val s = Skins.blank()
        val hat = Skins.parts(SkinModel.CLASSIC).first { it.key == "hat" }.face(Face.FRONT)
        s[hat.x, hat.y] = blue
        val front = Skins.preview(s, SkinModel.CLASSIC)
        assertEquals(16, front.width); assertEquals(32, front.height)
        assertEquals("overlay wins over the base layer", blue, front[4, 0])
        assertTrue(Skins.alpha(front[0, 8]) > 0) // arm drawn
        assertTrue(Skins.alpha(Skins.preview(s, SkinModel.CLASSIC, back = true)[8, 4]) > 0)
    }

    @Test fun `fill stays inside the face`() {
        val s = Skins.blank()
        val face = Skins.parts(SkinModel.CLASSIC).first { it.key == "body" }.face(Face.FRONT)
        assertTrue(Skins.fill(s, SkinModel.CLASSIC, face.x, face.y, red))
        assertEquals(red, s[face.x + face.w - 1, face.y + face.h - 1])
        assertTrue("neighbouring face untouched", s[face.x + face.w, face.y] != red)
        assertFalse(Skins.fill(s, SkinModel.CLASSIC, 0, 0, red))
    }

    @Test fun `photo becomes the face and hair`() {
        val w = 40; val h = 40
        val photo = IntArray(w * h) { i -> if (i / w < 8) blue else red }
        val skin = Skins.fromPhoto(photo, w, h, Skins.blank(), SkinModel.CLASSIC, cropY = 0.5f)
        val head = Skins.parts(SkinModel.CLASSIC).first { it.key == "head" }
        val face = head.face(Face.FRONT)
        assertEquals(blue, skin[face.x, face.y]); assertEquals(red, skin[face.x + 4, face.y + 7])
        assertEquals(blue, skin[head.face(Face.TOP).x, head.face(Face.TOP).y])
        assertEquals(0, Skins.alpha(skin[Skins.parts(SkinModel.CLASSIC).first { it.key == "hat" }.face(Face.FRONT).x, 8]))
    }

    @Test fun `bedrock skin pack has manifest, skins json and texture`() {
        val bytes = Skins.bedrockSkinPack("Night <Rider>", byteArrayOf(1, 2, 3), SkinModel.SLIM)
        val names = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes() } }
        assertEquals(setOf("manifest.json", "skins.json", "skin0.png", "texts/en_US.lang", "texts/languages.json"), names.keys)
        assertTrue(names.getValue("manifest.json").decodeToString().contains("skin_pack"))
        assertTrue(names.getValue("skins.json").decodeToString().contains("geometry.humanoid.customSlim"))
        assertFalse(names.getValue("texts/en_US.lang").decodeToString().contains("<"))
        assertArrayEquals(byteArrayOf(1, 2, 3), names.getValue("skin0.png"))
    }
}

class ElyByTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun <T> withServer(vararg responses: MockResponse, block: suspend (MockWebServer) -> T): T {
        val s = MockWebServer(); responses.forEach(s::enqueue); s.start()
        try { return kotlinx.coroutines.runBlocking { block(s) } } finally { s.shutdown() }
    }

    @Test fun `sign in, two-factor and wrong password`() {
        val ok = MockResponse().setBody("""{"accessToken":"AT","clientToken":"CT","selectedProfile":{"id":"ffc8fdc95824509e8a57c99b940fb996","name":"ErickSkrauch"}}""")
        withServer(MockResponse().setResponseCode(401).setBody("""{"error":"ForbiddenOperationException","errorMessage":"Account protected with two factor auth."}"""), ok) { s ->
            val auth = ElyByAuth(OkHttpClient(), s.url("").toString().trimEnd('/'))
            assertTrue(runCatching { auth.authenticate("user", "pass", null, "CT") }.exceptionOrNull() is ElyAuthException.TwoFactorRequired)
            val session = auth.authenticate("user", "pass", "123456", "CT")
            assertEquals("ErickSkrauch", session.username); assertEquals("ffc8fdc9-5824-509e-8a57-c99b940fb996", session.uuid)
            assertFalse(session.toString().contains("AT"))
            s.takeRequest()
            assertTrue("TOTP is appended to the password", s.takeRequest().body.readUtf8().contains("pass:123456"))
        }
        withServer(MockResponse().setResponseCode(401).setBody("""{"error":"ForbiddenOperationException","errorMessage":"Invalid credentials. Invalid email or password."}""")) { s ->
            assertTrue(runCatching { ElyByAuth(OkHttpClient(), s.url("").toString().trimEnd('/')).authenticate("u", "p", null, "c") }.exceptionOrNull() is ElyAuthException.InvalidCredentials)
        }
    }

    @Test fun `refresh and validate`() {
        withServer(MockResponse().setResponseCode(204), MockResponse().setResponseCode(401), MockResponse().setResponseCode(401)) { s ->
            val auth = ElyByAuth(OkHttpClient(), s.url("").toString().trimEnd('/'))
            assertTrue(auth.validate(ElyTokens("a", "c")))
            assertFalse(auth.validate(ElyTokens("a", "c")))
            assertTrue(runCatching { auth.refresh(ElyTokens("a", "c")) }.exceptionOrNull() is ElyAuthException.SessionExpired)
        }
    }

    @Test fun `skin upload sends variant and png with bearer token`() {
        withServer(MockResponse().setBody("{}"), MockResponse().setResponseCode(401)) { s ->
            val api = MinecraftSkinApi(OkHttpClient(), s.url("/skins").toString())
            api.upload("MCTOKEN", byteArrayOf(9, 9), SkinModel.SLIM)
            val r = s.takeRequest()
            assertEquals("Bearer MCTOKEN", r.getHeader("Authorization"))
            val body = r.body.readUtf8()
            assertTrue(body.contains("name=\"variant\"") && body.contains("slim") && body.contains("filename=\"skin.png\""))
            assertTrue(runCatching { api.upload("bad", byteArrayOf(1), SkinModel.CLASSIC) }.exceptionOrNull() is dev.eloa.launcher.domain.java.auth.AuthException.Session)
        }
    }

    @Test fun `authlib-injector is kept only with a matching checksum`() {
        val jar = ByteArray(1000) { (it % 13).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) }
        withServer() { s ->
            val meta = """{"build_number":53,"version":"1.2.5","download_url":"${s.url("/a.jar")}","checksums":{"sha256":"$sha"}}"""
            s.enqueue(MockResponse().setBody(meta)); s.enqueue(MockResponse().setBody(okio.Buffer().write(jar)))
            val d = AuthlibInjectorDownloader(OkHttpClient(), s.url("/latest.json").toString(), SourcePolicy { true })
            val out = d.ensure(tmp.root.resolve("authlib-injector.jar"))
            assertArrayEquals(jar, out.readBytes())
            s.enqueue(MockResponse().setBody(meta.replace(sha, "0".repeat(64)))); s.enqueue(MockResponse().setBody(okio.Buffer().write(jar)))
            assertTrue(runCatching { d.ensure(tmp.root.resolve("other.jar")) }.isFailure)
            assertFalse(tmp.root.resolve("other.jar").exists())
        }
    }
}
