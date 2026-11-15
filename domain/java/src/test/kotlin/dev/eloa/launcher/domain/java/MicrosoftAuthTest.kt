package dev.eloa.launcher.domain.java

import dev.eloa.launcher.domain.java.auth.AuthEndpoints
import dev.eloa.launcher.domain.java.auth.AuthException
import dev.eloa.launcher.domain.java.auth.MicrosoftAuth
import dev.eloa.launcher.domain.java.auth.StoredTokens
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MicrosoftAuthTest {
    private lateinit var server: MockWebServer
    private val polls = AtomicInteger()
    var xstsResponse = MockResponse().setBody("""{"Token":"XSTS","DisplayClaims":{"xui":[{"uhs":"UHS","xid":"2535"}]}}""")
    var entitlements = """{"items":[{"name":"product_minecraft"},{"name":"game_minecraft"}]}"""
    var profileCode = 200
    var tokenError: String? = null

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/devicecode" -> MockResponse().setBody("""{"user_code":"ABCD-1234","device_code":"DEV","verification_uri":"https://microsoft.com/link","expires_in":900,"interval":1}""")
                "/token" -> {
                    val body = request.body.readUtf8()
                    when {
                        tokenError != null -> MockResponse().setResponseCode(400).setBody("""{"error":"$tokenError"}""")
                        body.contains("refresh_token") -> MockResponse().setBody("""{"access_token":"MSA2","refresh_token":"R2","expires_in":3600}""")
                        polls.incrementAndGet() < 2 -> MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}""")
                        else -> MockResponse().setBody("""{"access_token":"MSA","refresh_token":"R1","expires_in":3600}""")
                    }
                }
                "/xbl" -> MockResponse().setBody("""{"Token":"XBL","DisplayClaims":{"xui":[{"uhs":"UHS"}]}}""")
                "/xsts" -> xstsResponse
                "/mc" -> {
                    assertEquals("""{"identityToken":"XBL3.0 x=UHS;XSTS"}""", request.body.readUtf8())
                    MockResponse().setBody("""{"access_token":"MCTOKEN","expires_in":86400}""")
                }
                "/ent" -> MockResponse().setBody(entitlements)
                "/profile" -> if (profileCode == 200) MockResponse().setBody("""{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","skins":[{"state":"ACTIVE","url":"https://textures.minecraft.net/x"}]}""")
                    else MockResponse().setResponseCode(profileCode)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun auth(now: () -> Long = System::currentTimeMillis) = MicrosoftAuth(OkHttpClient(), "client-id", AuthEndpoints(
        server.url("/devicecode").toString(), server.url("/token").toString(), server.url("/xbl").toString(), server.url("/xsts").toString(),
        server.url("/mc").toString(), server.url("/ent").toString(), server.url("/profile").toString(),
    ), now)

    @Test fun `full device code sign-in`() = runTest {
        val a = auth()
        val code = a.requestDeviceCode()
        assertEquals("ABCD-1234", code.userCode)
        assertFalse(code.toString().contains("DEV,"))
        val tokens = a.pollForTokens(code)
        assertEquals(2, polls.get())
        val s = a.loginMinecraft(tokens)
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", s.uuid)
        assertEquals("Notch", s.username); assertEquals("2535", s.xuid); assertEquals("R1", s.msRefreshToken)
        assertFalse(s.toString().contains("MCTOKEN"))
    }

    @Test fun `xsts errors are mapped`() = runTest {
        xstsResponse = MockResponse().setResponseCode(401).setBody("""{"XErr":2148916233}""")
        var e: Exception? = null
        try { auth().loginMinecraft(dev.eloa.launcher.domain.java.auth.MsTokens("a", "r", 0)) } catch (x: AuthException) { e = x }
        assertTrue(e is AuthException.NoXboxAccount)
        xstsResponse = MockResponse().setResponseCode(401).setBody("""{"XErr":2148916238}""")
        try { auth().loginMinecraft(dev.eloa.launcher.domain.java.auth.MsTokens("a", "r", 0)) } catch (x: AuthException) { e = x }
        assertTrue(e is AuthException.ChildAccount)
    }

    @Test fun `ownership is required`() = runTest {
        entitlements = """{"items":[]}"""
        var e: Exception? = null
        try { auth().loginMinecraft(dev.eloa.launcher.domain.java.auth.MsTokens("a", "r", 0)) } catch (x: AuthException) { e = x }
        assertTrue(e is AuthException.NotOwned)
        entitlements = """{"items":[{"name":"game_minecraft"}]}"""
        profileCode = 404
        try { auth().loginMinecraft(dev.eloa.launcher.domain.java.auth.MsTokens("a", "r", 0)) } catch (x: AuthException) { e = x }
        assertTrue(e is AuthException.NoProfile)
    }

    @Test fun `refresh flow and expiry handling`() = runTest {
        val t0 = 1_000_000L
        val a = auth { t0 }
        assertNull(a.ensureFresh(StoredTokens("R1", "MC", t0 + 3_600_000)))
        val refreshed = a.ensureFresh(StoredTokens("R1", "MC", t0 + 1000))
        assertEquals("R2", refreshed!!.msRefreshToken)
        tokenError = "invalid_grant"
        var e: Exception? = null
        try { a.refresh("R1") } catch (x: AuthException) { e = x }
        assertTrue(e is AuthException.Session)
    }

    @Test fun `declined and invalid client`() = runTest {
        tokenError = "authorization_declined"
        var e: Exception? = null
        try { auth().pollForTokens(auth().requestDeviceCode()) } catch (x: AuthException) { e = x }
        assertTrue(e is AuthException.Declined)
        tokenError = "invalid_client"
        try { auth().pollForTokens(auth().requestDeviceCode()) } catch (x: AuthException) { e = x }
        assertTrue(e is AuthException.InvalidClient)
    }

    @Test(expected = IllegalArgumentException::class) fun `blank client id rejected`() { MicrosoftAuth(OkHttpClient(), " ") }
}
