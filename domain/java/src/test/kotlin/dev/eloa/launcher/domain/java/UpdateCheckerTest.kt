package dev.eloa.launcher.domain.java

import dev.eloa.launcher.domain.java.net.SourcePolicy
import dev.eloa.launcher.domain.java.net.UntrustedSourceException
import dev.eloa.launcher.domain.java.update.UpdateChecker
import dev.eloa.launcher.domain.java.update.UpdateIntegrityException
import dev.eloa.launcher.domain.java.update.UpdateResult
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private var shaAsset = true
    private var corrupt = false

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                return when (request.path) {
                    "/repos/o/r/releases?per_page=10" -> MockResponse().setBody("""[
                        {"tag_name":"v1.3.0-beta","prerelease":true,"assets":[]},
                        {"tag_name":"v1.2.0","name":"1.2.0","body":"Notes","html_url":"$base/rel","assets":[
                          {"name":"eloa-1.2.0.apk","browser_download_url":"$base/apk","size":${apk.size}},
                          {"name":"eloa-debug.apk","browser_download_url":"$base/dbg","size":1}
                          ${if (shaAsset) """,{"name":"eloa-1.2.0.apk.sha256","browser_download_url":"$base/sha","size":64}""" else ""}
                        ]},
                        {"tag_name":"v1.1.0","assets":[]}
                    ]""")
                    "/sha" -> MockResponse().setBody("$sha  eloa-1.2.0.apk\n")
                    "/apk" -> MockResponse().setBody(okio.Buffer().write(if (corrupt) apk.copyOf().also { it[5] = 9 } else apk))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun checker() = UpdateChecker(OkHttpClient(), "o/r", server.url("/").toString().trimEnd('/'), SourcePolicy { true })

    @Test fun `newer stable release with checksum is offered and verified`() = runTest {
        val r = checker().check("1.0.0") as UpdateResult.Available
        assertEquals("1.2.0", r.info.version)
        assertEquals("eloa-1.2.0.apk", r.info.apk.name)
        assertEquals(sha, r.info.sha256)
        val out = tmp.root.resolve("u.apk")
        var last = 0L
        checker().download(r.info, out) { d, _ -> last = d }
        assertEquals(apk.size.toLong(), last)
        assertTrue(out.isFile)
    }

    @Test fun `prereleases only on request and up-to-date detection`() = runTest {
        assertEquals("1.2.0", (checker().check("v1.2.0") as UpdateResult.UpToDate).latest)
        // The newer prerelease publishes no APK: reported as such, never as an installable update.
        assertEquals("1.3.0", (checker().check("1.2.0", includePrereleases = true) as UpdateResult.NoApk).latest)
    }

    @Test fun `tampered download is deleted`() = runTest {
        corrupt = true
        val info = (checker().check("1.0") as UpdateResult.Available).info
        val out = tmp.root.resolve("u.apk")
        var failed = false
        try { checker().download(info, out) } catch (e: UpdateIntegrityException) { failed = true }
        assertTrue(failed); assertFalse(out.exists())
    }

    @Test fun `missing checksum refuses to download`() = runTest {
        shaAsset = false
        val info = (checker().check("1.0") as UpdateResult.Available).info
        assertNull(info.sha256)
        var failed = false
        try { checker().download(info, tmp.root.resolve("x.apk")) } catch (e: UpdateIntegrityException) { failed = true }
        assertTrue(failed)
    }

    @Test fun `github policy rejects other hosts and plain http`() = runTest {
        var failed = false
        try { UpdateChecker(OkHttpClient(), "o/r", server.url("/").toString().trimEnd('/')).check("1.0") } catch (e: UntrustedSourceException) { failed = true }
        assertTrue(failed)
        assertEquals("abc".padEnd(64, '0'), UpdateChecker.parseSha("ABC".padEnd(64, '0'), "f.apk"))
        assertNull(UpdateChecker.parseSha("${"a".repeat(64)}  other.apk\n${"b".repeat(64)}  third.apk", "f.apk"))
    }

    @Test(expected = IllegalArgumentException::class) fun `repo slug is validated`() { UpdateChecker(OkHttpClient(), "../evil") }
}
