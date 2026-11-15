package dev.eloa.launcher.domain.java

import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.domain.java.download.DownloadConfig
import dev.eloa.launcher.domain.java.download.DownloadManager
import dev.eloa.launcher.domain.java.download.DownloadProgress
import dev.eloa.launcher.domain.java.download.DownloadRequest
import dev.eloa.launcher.domain.java.download.InsufficientSpaceException
import dev.eloa.launcher.domain.java.net.SourcePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DownloadManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val allowAll = SourcePolicy { true }
    private val fast = DownloadConfig(concurrency = 4, maxRetries = 2, retryBaseDelayMs = 10, reserveBytes = 0, progressIntervalMs = 20)

    private val payloads = (1..5).associate { "/f$it" to ByteArray(10_000 * it) { b -> ((b * 31 + it) % 256).toByte() } }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun sha1(b: ByteArray) = Hashing.digest(b, HashAlgorithm.SHA1)
    private fun req(path: String, sha: String? = sha1(payloads.getValue(path)), size: Long = payloads.getValue(path).size.toLong()) =
        DownloadRequest(server.url(path).toString(), File(tmp.root, "out$path.bin"), sha, size)

    private fun serveAll(rangeSupport: Boolean = true, failFirst: Set<String> = emptySet()) {
        val seen = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                val data = payloads[path] ?: return MockResponse().setResponseCode(404)
                if (path in failFirst && seen.getOrPut(path) { AtomicInteger() }.incrementAndGet() == 1) return MockResponse().setResponseCode(503)
                val range = request.getHeader("Range")
                if (rangeSupport && range != null) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse().setResponseCode(206).setBody(Buffer().write(data.copyOfRange(from, data.size)))
                }
                return MockResponse().setBody(Buffer().write(data))
            }
        }
    }

    @Test fun `downloads in parallel with verification and progress`() = runBlocking {
        serveAll()
        val events = mutableListOf<DownloadProgress>()
        val dm = DownloadManager(client, allowAll, fast)
        val summary = dm.downloadAll(payloads.keys.map { req(it) }) { synchronized(events) { events += it } }
        assertTrue(summary.isSuccess)
        assertEquals(5, summary.downloaded)
        payloads.forEach { (p, d) -> assertArrayEquals(d, File(tmp.root, "out$p.bin").readBytes()) }
        val last = events.last()
        assertEquals(5, last.completedFiles); assertEquals(1f, last.fraction, 0.001f)
        assertEquals(payloads.values.sumOf { it.size }.toLong(), last.downloadedBytes)
        assertFalse(File(tmp.root, "out").walk().any { it.name.endsWith(".part") })
    }

    @Test fun `already valid files are skipped`() = runBlocking {
        serveAll()
        val dm = DownloadManager(client, allowAll, fast)
        dm.downloadAll(listOf(req("/f1")))
        val before = server.requestCount
        val s = dm.downloadAll(listOf(req("/f1")))
        assertEquals(1, s.skipped); assertEquals(before, server.requestCount)
    }

    @Test fun `resumes partial download with range`() = runBlocking {
        serveAll()
        val r = req("/f3")
        r.target.parentFile.mkdirs()
        val part = File(r.target.parentFile, r.target.name + ".part")
        part.writeBytes(payloads.getValue("/f3").copyOf(12_345))
        val s = DownloadManager(client, allowAll, fast).downloadAll(listOf(r))
        assertTrue(s.isSuccess)
        assertEquals("bytes=12345-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(payloads.getValue("/f3"), r.target.readBytes())
    }

    @Test fun `server without range support restarts cleanly`() = runBlocking {
        serveAll(rangeSupport = false)
        val r = req("/f2")
        r.target.parentFile.mkdirs()
        File(r.target.parentFile, r.target.name + ".part").writeBytes(ByteArray(500) { 9 })
        assertTrue(DownloadManager(client, allowAll, fast).downloadAll(listOf(r)).isSuccess)
        assertArrayEquals(payloads.getValue("/f2"), r.target.readBytes())
    }

    @Test fun `transient errors are retried`() = runBlocking {
        serveAll(failFirst = setOf("/f1", "/f2"))
        val s = DownloadManager(client, allowAll, fast).downloadAll(listOf(req("/f1"), req("/f2")))
        assertTrue(s.failures.toString(), s.isSuccess)
    }

    @Test fun `404 fails permanently without retries`() = runBlocking {
        serveAll()
        val r = DownloadRequest(server.url("/missing").toString(), File(tmp.root, "m.bin"))
        val s = DownloadManager(client, allowAll, fast).downloadAll(listOf(r))
        assertEquals(1, s.failures.size); assertTrue(s.failures[0].permanent); assertEquals(1, server.requestCount)
    }

    @Test fun `checksum mismatch fails after retries and leaves no file`() = runBlocking {
        serveAll()
        val r = req("/f1", sha = "0".repeat(40))
        val s = DownloadManager(client, allowAll, fast).downloadAll(listOf(r))
        assertEquals(1, s.failures.size)
        assertTrue(s.failures[0].reason.contains("SHA-1"))
        assertFalse(r.target.exists())
        assertEquals(1 + fast.maxRetries, server.requestCount)
    }

    @Test fun `size mismatch detected`() = runBlocking {
        serveAll()
        val s = DownloadManager(client, allowAll, fast).downloadAll(listOf(req("/f1", sha = null, size = 5)))
        assertEquals(1, s.failures.size)
    }

    @Test fun `untrusted source rejected by official policy`() = runBlocking {
        serveAll()
        val s = DownloadManager(client, SourcePolicy.OFFICIAL, fast).downloadAll(listOf(req("/f1")))
        assertTrue(s.failures.single().permanent)
        assertEquals(0, server.requestCount)
        assertFalse(SourcePolicy.OFFICIAL.isAllowed(okhttp3.HttpUrl.Builder().scheme("http").host("libraries.minecraft.net").build()))
        assertTrue(SourcePolicy.OFFICIAL.isAllowed(okhttp3.HttpUrl.Builder().scheme("https").host("libraries.minecraft.net").build()))
    }

    @Test(expected = InsufficientSpaceException::class) fun `insufficient space aborts before downloading`() = runBlocking<Unit> {
        serveAll()
        DownloadManager(client, allowAll, fast, freeSpace = { 1000 }).downloadAll(listOf(req("/f5")))
    }

    @Test fun `cancellation stops download and keeps partial for resume`() = runBlocking {
        val big = ByteArray(2_000_000) { 1 }
        server.enqueue(MockResponse().setBody(Buffer().write(big)).throttleBody(50_000, 100, TimeUnit.MILLISECONDS))
        val r = DownloadRequest(server.url("/big").toString(), File(tmp.root, "big.bin"), null, big.size.toLong())
        val dm = DownloadManager(client, allowAll, fast)
        val job = launch(Dispatchers.IO) { dm.downloadAll(listOf(r)) }
        withContext(Dispatchers.IO) { delay(400) }
        job.cancel(); job.join()
        assertFalse(r.target.exists())
        assertTrue(File(tmp.root, "big.bin.part").exists())
    }

    @Test fun `stress many small files with flaky server`() = runBlocking {
        val files = (1..200).associate { "/s$it" to ByteArray(100 + it) { b -> (b xor it).toByte() } }
        val attempts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                val n = attempts.getOrPut(p) { AtomicInteger() }.incrementAndGet()
                if (n == 1 && p.removePrefix("/s").toInt() % 7 == 0) return MockResponse().setResponseCode(500)
                return MockResponse().setBody(Buffer().write(files.getValue(p)))
            }
        }
        val reqs = files.map { (p, d) -> DownloadRequest(server.url(p).toString(), File(tmp.root, "s$p"), sha1(d), d.size.toLong()) }
        val s = DownloadManager(client, allowAll, fast.copy(concurrency = 8)).downloadAll(reqs)
        assertTrue(s.failures.toString(), s.isSuccess)
        assertEquals(200, s.downloaded)
    }

    @Test fun `cleanup removes stale partial files`() {
        val dir = tmp.newFolder("c")
        val old = File(dir, "a.part").apply { writeBytes(ByteArray(100)); setLastModified(1000) }
        val fresh = File(dir, "b.part").apply { writeBytes(ByteArray(10)) }
        assertEquals(100, DownloadManager.cleanupPartials(dir, maxAgeMs = 60_000))
        assertFalse(old.exists()); assertTrue(fresh.exists())
    }
}
