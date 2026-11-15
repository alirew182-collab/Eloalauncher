package dev.eloa.launcher.domain.java.install

import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.common.logging.NoOpLogger
import dev.eloa.launcher.domain.java.download.ChecksumMismatchException
import dev.eloa.launcher.domain.java.download.DownloadManager
import dev.eloa.launcher.domain.java.download.DownloadProgress
import dev.eloa.launcher.domain.java.download.DownloadRequest
import dev.eloa.launcher.domain.java.download.DownloadSummary
import dev.eloa.launcher.domain.java.meta.AssetIndex
import dev.eloa.launcher.domain.java.meta.RuleEnvironment
import dev.eloa.launcher.domain.java.meta.VersionJson
import dev.eloa.launcher.domain.java.meta.VersionManifest
import dev.eloa.launcher.domain.java.meta.VersionResolver
import dev.eloa.launcher.domain.java.net.MetaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class InstallStage { METADATA, LIBRARIES, CLIENT, ASSET_INDEX, ASSETS, NATIVES, VERIFY, DONE }

class InstallFailedException(message: String, val summary: DownloadSummary? = null) : IOException(message)

/** Result of an integrity check of an installed version. */
data class IntegrityReport(val checked: Int, val missing: List<File>, val corrupted: List<File>) {
    val ok get() = missing.isEmpty() && corrupted.isEmpty()
}

/**
 * Installs vanilla versions (and the libraries of loader profiles) into [GameDirectories],
 * downloading only from official sources and verifying every file.
 */
class VersionInstaller(
    private val meta: MetaClient,
    private val downloads: DownloadManager,
    private val dirs: GameDirectories,
    private val env: RuleEnvironment,
    private val logger: AppLogger = NoOpLogger,
) {
    /** Downloads (or reads cached) version.json for a manifest entry, verifying its SHA-1. */
    suspend fun fetchVersionJson(entry: VersionManifest.Entry): VersionJson = withContext(Dispatchers.IO) {
        val file = dirs.versionJson(entry.id)
        val cachedOk = file.isFile && (entry.sha1 == null || Hashing.verify(file, entry.sha1, HashAlgorithm.SHA1))
        val text = if (cachedOk) file.readText() else {
            val t = meta.getText(entry.url)
            if (entry.sha1 != null && Hashing.digest(t.toByteArray(), HashAlgorithm.SHA1) != entry.sha1.lowercase()) {
                throw ChecksumMismatchException("version.json checksum mismatch for ${entry.id}")
            }
            FileOps.writeAtomically(file, t.toByteArray())
            t
        }
        VersionJson.parse(text)
    }

    fun loadLocal(id: String): VersionJson {
        val f = dirs.versionJson(id)
        if (!f.isFile) throw IOException("Version $id is not installed")
        return VersionJson.parse(f.readText())
    }

    suspend fun resolveLocal(id: String): VersionJson = VersionResolver.resolve(loadLocal(id)) { loadLocal(it) }

    /** Installs everything needed to launch [version] (already resolved, i.e. no inheritsFrom). */
    suspend fun installResolved(
        version: VersionJson,
        clientId: String,
        onStage: (InstallStage) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadSummary {
        require(version.inheritsFrom == null) { "Version must be resolved first" }
        onStage(InstallStage.LIBRARIES)
        val libs = LibraryPlanner.resolve(version, dirs, env)
        val requests = ArrayList<DownloadRequest>()
        libs.mapNotNullTo(requests) { it.download }
        version.downloads?.client?.let { requests += DownloadRequest(it.url, dirs.clientJar(clientId), it.sha1, it.size, "$clientId.jar") }
        version.logging?.client?.file?.let { lf ->
            requests += DownloadRequest(lf.url, File(dirs.assets, "log_configs/${lf.id}"), lf.sha1, lf.size, lf.id)
        }
        version.assetIndex?.let { ai -> requests += DownloadRequest(ai.url, dirs.assetIndex(ai.id), ai.sha1, ai.size, "asset index ${ai.id}") }
        val first = downloads.downloadAll(requests, onProgress)
        if (!first.isSuccess) throw InstallFailedException("${first.failures.size} file(s) failed: ${first.failures.first().reason}", first)

        val assetSummary = version.assetIndex?.let { ai ->
            onStage(InstallStage.ASSETS)
            val index = AssetIndex.parse(dirs.assetIndex(ai.id).readText())
            val assetRequests = index.objects.values.distinctBy { it.hash }.map { o ->
                DownloadRequest(AssetIndex.objectUrl(o.hash), dirs.assetObject(o.hash), o.hash, o.size, "asset ${o.hash.take(8)}")
            }
            val s = downloads.downloadAll(assetRequests, onProgress)
            if (!s.isSuccess) throw InstallFailedException("${s.failures.size} asset(s) failed: ${s.failures.first().reason}", s)
            if (index.virtual || index.map_to_resources) materializeLegacyAssets(ai.id, index)
            s
        }
        onStage(InstallStage.NATIVES)
        extractNatives(version, clientId)
        onStage(InstallStage.DONE)
        logger.i("Installer", "Installed $clientId", mapOf("libraries" to libs.size.toString()))
        return DownloadSummary(
            first.downloaded + (assetSummary?.downloaded ?: 0), first.skipped + (assetSummary?.skipped ?: 0),
            emptyList(), first.bytes + (assetSummary?.bytes ?: 0),
        )
    }

    suspend fun installVanilla(entry: VersionManifest.Entry, onStage: (InstallStage) -> Unit = {}, onProgress: (DownloadProgress) -> Unit = {}): VersionJson {
        onStage(InstallStage.METADATA)
        val v = fetchVersionJson(entry)
        installResolved(v, v.id, onStage, onProgress)
        return v
    }

    private fun materializeLegacyAssets(indexId: String, index: AssetIndex) {
        val target = dirs.legacyAssets(indexId)
        for ((path, obj) in index.objects) {
            val dst = dev.eloa.launcher.core.common.io.PathSafety.resolveInside(target, path)
            if (dst.isFile && dst.length() == obj.size) continue
            dst.parentFile?.mkdirs()
            dirs.assetObject(obj.hash).copyTo(dst, overwrite = true)
        }
    }

    /** Extracts legacy native jars (excluding META-INF) into versions/<id>/natives. */
    fun extractNatives(version: VersionJson, clientId: String) {
        val natives = LibraryPlanner.resolve(version, dirs, env).filter { it.isNative && it.file.isFile }
        if (natives.isEmpty()) return
        val out = dirs.nativesDir(clientId)
        out.mkdirs()
        for (n in natives) {
            val excludes = n.library.extract?.exclude.orEmpty() + "META-INF/"
            dev.eloa.launcher.core.common.archive.SafeZip.open(n.file).use { zip ->
                zip.extractTo(out, filter = { name -> excludes.none { name.startsWith(it) } && !name.endsWith("/") })
            }
        }
    }

    /** Verifies presence, size and SHA-1 of all files of an installed version. */
    suspend fun verify(version: VersionJson, clientId: String, deep: Boolean = true): IntegrityReport = withContext(Dispatchers.IO) {
        val missing = ArrayList<File>()
        val corrupted = ArrayList<File>()
        var checked = 0
        val reqs = LibraryPlanner.resolve(version, dirs, env).mapNotNull { it.download }.toMutableList()
        version.downloads?.client?.let { reqs += DownloadRequest(it.url, dirs.clientJar(clientId), it.sha1, it.size) }
        version.assetIndex?.let { ai ->
            val idx = dirs.assetIndex(ai.id)
            if (idx.isFile) AssetIndex.parse(idx.readText()).objects.values.distinctBy { it.hash }
                .forEach { reqs += DownloadRequest(AssetIndex.objectUrl(it.hash), dirs.assetObject(it.hash), it.hash, it.size) }
            else missing += idx
        }
        for (r in reqs) {
            checked++
            val f = r.target
            when {
                !f.isFile -> missing += f
                r.size >= 0 && f.length() != r.size -> corrupted += f
                deep && r.sha1 != null && !Hashing.verify(f, r.sha1, HashAlgorithm.SHA1) -> corrupted += f
            }
        }
        IntegrityReport(checked, missing, corrupted)
    }
}
