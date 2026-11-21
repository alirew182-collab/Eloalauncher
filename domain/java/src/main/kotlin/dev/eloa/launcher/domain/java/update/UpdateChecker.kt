package dev.eloa.launcher.domain.java.update

import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.version.ComparableVersion
import dev.eloa.launcher.domain.java.net.HttpStatusException
import dev.eloa.launcher.domain.java.net.LauncherJson
import dev.eloa.launcher.domain.java.net.SourcePolicy
import dev.eloa.launcher.domain.java.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

@Serializable
data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(
    val version: String,
    val notes: String,
    val pageUrl: String?,
    val apk: GhAsset,
    /** Expected SHA-256 of the APK, from a `.sha256` asset or `SHA256SUMS`. Null = cannot be verified. */
    val sha256: String?,
    val publishedAt: String?,
)

sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    data class UpToDate(val latest: String) : UpdateResult
    /** A release exists but carries no APK for this app. */
    data class NoApk(val latest: String) : UpdateResult
}

class UpdateIntegrityException(message: String) : IOException(message)

/**
 * Checks GitHub Releases of the launcher repository. Updates are only offered with a published
 * SHA-256; the downloaded APK is hashed before it is handed to the system installer, and the
 * caller additionally verifies the signing certificate equals the installed app's.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val repo: String,
    private val apiBase: String = "https://api.github.com",
    private val policy: SourcePolicy = GITHUB,
) {
    init {
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo) && repo.split('/').none { it == "." || it == ".." }) { "Invalid repository slug" }
    }

    suspend fun check(currentVersion: String, includePrereleases: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        val releases = LauncherJson.decodeFromString<List<GhRelease>>(getText("$apiBase/repos/$repo/releases?per_page=10"))
        val rel = releases.filter { !it.draft && (includePrereleases || !it.prerelease) }
            .maxByOrNull { ComparableVersion(normalize(it.tag)) } ?: return@withContext UpdateResult.UpToDate(currentVersion)
        val latest = normalize(rel.tag)
        if (ComparableVersion(latest) <= ComparableVersion(normalize(currentVersion))) return@withContext UpdateResult.UpToDate(latest)
        val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("debug", true) }
            ?: return@withContext UpdateResult.NoApk(latest)
        val sha = rel.assets.firstOrNull { it.name == apk.name + ".sha256" }?.let { parseSha(getText(it.url), apk.name) }
            ?: rel.assets.firstOrNull { it.name.equals("SHA256SUMS", true) || it.name.equals("SHA256SUMS.txt", true) }?.let { parseSha(getText(it.url), apk.name) }
        UpdateResult.Available(UpdateInfo(latest, rel.body.orEmpty().take(20_000), rel.htmlUrl, apk, sha, rel.publishedAt))
    }

    /** Downloads the APK into [target] and verifies its SHA-256. Deletes the file on any failure. */
    suspend fun download(info: UpdateInfo, target: File, maxBytes: Long = 300L * 1024 * 1024, onProgress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val expected = info.sha256 ?: throw UpdateIntegrityException("The release does not publish a SHA-256 checksum; refusing to install an unverifiable update.")
        val url = SourcePolicy.check(policy, info.apk.url)
        target.parentFile?.mkdirs()
        try {
            http.newCall(Request.Builder().url(url).build()).await().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apk.size
                if (total > maxBytes) throw IOException("Update is too large")
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > maxBytes) throw IOException("Update is too large")
                            out.write(buf, 0, n)
                            onProgress(done, total)
                        }
                    }
                }
            }
            val actual = Hashing.digest(target, HashAlgorithm.SHA256)
            if (!actual.equals(expected, ignoreCase = true)) throw UpdateIntegrityException("Checksum mismatch: the downloaded update is damaged or was modified.")
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private suspend fun getText(url: String): String {
        val parsed = SourcePolicy.check(policy, url)
        val req = Request.Builder().url(parsed).header("Accept", "application/vnd.github+json").build()
        return http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code} for ${parsed.host}")
            val src = resp.body?.source() ?: throw IOException("Empty body")
            src.request(MAX_TEXT + 1)
            if (src.buffer.size > MAX_TEXT) throw IOException("Response too large")
            src.buffer.readUtf8()
        }
    }

    companion object {
        private const val MAX_TEXT = 2L * 1024 * 1024
        val GITHUB_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        val GITHUB = SourcePolicy { it.isHttps && it.host in GITHUB_HOSTS }

        fun normalize(tag: String) = tag.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')

        /** Accepts `<hex>` or `<hex>  <file>` lines (sha256sum format). */
        fun parseSha(text: String, fileName: String): String? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val hex = Regex("^[0-9a-fA-F]{64}")
            lines.firstOrNull { l -> l.split(Regex("\\s+")).getOrNull(1)?.removePrefix("*") == fileName }?.let { return hex.find(it)?.value?.lowercase() }
            if (lines.size == 1) return hex.find(lines[0])?.value?.lowercase()
            return null
        }
    }
}
