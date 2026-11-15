package dev.eloa.launcher.domain.java.download

import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.common.logging.NoOpLogger
import dev.eloa.launcher.domain.java.net.HttpStatusException
import dev.eloa.launcher.domain.java.net.SourcePolicy
import dev.eloa.launcher.domain.java.net.UntrustedSourceException
import dev.eloa.launcher.domain.java.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class DownloadRequest(
    val url: String,
    val target: File,
    val sha1: String? = null,
    val size: Long = -1,
    val label: String = target.name,
)

data class DownloadProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val failedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
    val active: List<String>,
) {
    val fraction: Float get() = when {
        totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        totalFiles > 0 -> (completedFiles + failedFiles).toFloat() / totalFiles
        else -> 0f
    }
}

data class DownloadFailure(val request: DownloadRequest, val reason: String, val permanent: Boolean)

data class DownloadSummary(val downloaded: Int, val skipped: Int, val failures: List<DownloadFailure>, val bytes: Long) {
    val isSuccess get() = failures.isEmpty()
}

data class DownloadConfig(
    val concurrency: Int = 6,
    val maxRetries: Int = 3,
    val retryBaseDelayMs: Long = 500,
    /** Extra free space kept untouched. */
    val reserveBytes: Long = 64L * 1024 * 1024,
    val progressIntervalMs: Long = 250,
)

class InsufficientSpaceException(val required: Long, val available: Long) :
    IOException("Not enough free space: need ${required / 1_048_576} MB, available ${available / 1_048_576} MB")

class ChecksumMismatchException(message: String) : IOException(message)

/**
 * Reliable parallel downloader:
 * bounded parallelism, resume via HTTP Range (`.part` files), SHA-1 + size verification,
 * retries with exponential backoff + jitter for transient errors, source allow-listing,
 * free space check, speed/ETA reporting and cooperative cancellation.
 */
class DownloadManager(
    private val client: OkHttpClient,
    private val policy: SourcePolicy = SourcePolicy.OFFICIAL,
    private val config: DownloadConfig = DownloadConfig(),
    private val freeSpace: (File) -> Long = { it.usableSpaceOfExistingParent() },
    private val logger: AppLogger = NoOpLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun downloadAll(requests: List<DownloadRequest>, onProgress: (DownloadProgress) -> Unit = {}): DownloadSummary = coroutineScope {
        val unique = requests.distinctBy { it.target.absolutePath }
        val totalBytes = unique.sumOf { it.size.coerceAtLeast(0) }
        val downloaded = AtomicLong(0)
        val completed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val transferred = AtomicLong(0)
        val failures = ConcurrentLinkedQueue<DownloadFailure>()
        val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        // Free space check for known sizes of files that are not already present.
        val pending = withContext(Dispatchers.IO) { unique.filter { !isAlreadyValid(it) } }
        val alreadyValid = unique.size - pending.size
        skipped.set(alreadyValid)
        completed.set(alreadyValid)
        unique.filter { it !in pending }.forEach { downloaded.addAndGet(it.size.coerceAtLeast(0)) }
        val required = pending.sumOf { r -> (r.size.coerceAtLeast(0) - partLength(r)).coerceAtLeast(0) }
        pending.firstOrNull()?.let { first ->
            val available = freeSpace(first.target)
            if (required + config.reserveBytes > available) throw InsufficientSpaceException(required + config.reserveBytes, available)
        }

        val reporter = launch {
            var lastBytes = transferred.get()
            var lastTime = clock()
            var speed = 0.0
            while (isActive) {
                delay(config.progressIntervalMs)
                val now = clock()
                val bytes = transferred.get()
                val dt = (now - lastTime).coerceAtLeast(1)
                val instant = (bytes - lastBytes) * 1000.0 / dt
                speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                lastBytes = bytes; lastTime = now
                onProgress(snapshot(unique.size, completed.get(), failures.size, totalBytes, downloaded.get(), speed, active.toList()))
            }
        }

        val semaphore = Semaphore(config.concurrency.coerceIn(1, 32))
        val jobs = pending.map { req ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    active += req.label
                    try {
                        downloadWithRetry(req) { n -> downloaded.addAndGet(n); transferred.addAndGet(n) }
                        completed.incrementAndGet()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val permanent = e is HttpStatusException && e.code in 400..499 && e.code != 408 && e.code != 429 ||
                            e is UntrustedSourceException
                        failures += DownloadFailure(req, e.message ?: e.javaClass.simpleName, permanent)
                        logger.w("Download", "Failed ${req.label}", e)
                    } finally {
                        active -= req.label
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        reporter.cancel()
        onProgress(snapshot(unique.size, completed.get(), failures.size, totalBytes, downloaded.get(), 0.0, emptyList()))
        DownloadSummary(completed.get() - skipped.get(), skipped.get(), failures.toList(), transferred.get())
    }

    private fun snapshot(total: Int, done: Int, failed: Int, totalBytes: Long, bytes: Long, speed: Double, active: List<String>): DownloadProgress {
        val remaining = (totalBytes - bytes).coerceAtLeast(0)
        val eta = if (speed > 1 && totalBytes > 0) (remaining / speed).toLong() else null
        return DownloadProgress(total, done, failed, totalBytes, bytes, speed.toLong(), eta, active)
    }

    private fun isAlreadyValid(r: DownloadRequest): Boolean {
        val f = r.target
        if (!f.isFile) return false
        if (r.size >= 0 && f.length() != r.size) return false
        return r.sha1 == null || Hashing.verify(f, r.sha1, HashAlgorithm.SHA1)
    }

    private fun partFile(r: DownloadRequest) = File(r.target.parentFile, r.target.name + ".part")
    private fun partLength(r: DownloadRequest) = partFile(r).let { if (it.isFile) it.length() else 0L }

    private suspend fun downloadWithRetry(req: DownloadRequest, onBytes: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val counted = AtomicLong(0)
            try {
                downloadOnce(req) { n -> counted.addAndGet(n); onBytes(n) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Undo this attempt's progress; a resumed attempt re-reports the kept .part length.
                onBytes(-counted.get())
                val permanent = (e is HttpStatusException && e.code in 400..499 && e.code != 408 && e.code != 429) || e is UntrustedSourceException
                attempt++
                if (permanent || attempt > config.maxRetries) throw e
                val backoff = config.retryBaseDelayMs * (1L shl (attempt - 1)) + Random.nextLong(0, config.retryBaseDelayMs / 2 + 1)
                logger.i("Download", "Retry $attempt for ${req.label} in ${backoff}ms", mapOf("reason" to (e.message ?: "")))
                delay(backoff)
            }
        }
    }

    private suspend fun downloadOnce(req: DownloadRequest, onBytes: (Long) -> Unit) {
        val url = SourcePolicy.check(policy, req.url)
        val target = req.target
        target.parentFile?.mkdirs()
        val part = partFile(req)
        var offset = if (part.isFile) part.length() else 0L
        if (req.size in 0..<offset) { part.delete(); offset = 0 }
        if (req.size >= 0 && offset == req.size) {
            finish(req, part)
            return
        }
        val builder = Request.Builder().url(url)
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        client.newCall(builder.build()).await().use { resp ->
            if (resp.code == 416) { part.delete(); throw IOException("Range not satisfiable, restarting") }
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code} for ${req.label}")
            val append = offset > 0 && resp.code == 206
            if (!append) offset = 0 // server ignored Range: start over
            if (append) onBytes(offset)
            val body = resp.body ?: throw IOException("Empty body for ${req.label}")
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = offset
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (req.size >= 0 && written > req.size) throw IOException("Server sent more data than expected for ${req.label}")
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
        finish(req, part)
    }

    private fun finish(req: DownloadRequest, part: File) {
        if (req.size >= 0 && part.length() != req.size) {
            throw IOException("Size mismatch for ${req.label}: ${part.length()} != ${req.size}")
        }
        if (req.sha1 != null && !Hashing.verify(part, req.sha1, HashAlgorithm.SHA1)) {
            part.delete()
            throw ChecksumMismatchException("SHA-1 mismatch for ${req.label}")
        }
        if (req.target.exists() && !req.target.delete()) throw IOException("Cannot replace ${req.label}")
        if (!part.renameTo(req.target)) throw IOException("Cannot move ${req.label} into place")
    }

    companion object {
        /** Deletes stale `.part` files older than [maxAgeMs]. Returns freed bytes. */
        fun cleanupPartials(root: File, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Long {
            if (!root.isDirectory) return 0
            var freed = 0L
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") && now - it.lastModified() > maxAgeMs }.forEach {
                val len = it.length(); if (it.delete()) freed += len
            }
            return freed
        }
    }
}

internal fun File.usableSpaceOfExistingParent(): Long {
    var f: File? = this
    while (f != null && !f.exists()) f = f.parentFile
    return f?.usableSpace ?: 0L
}
