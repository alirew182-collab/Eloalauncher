package dev.eloa.launcher.feature.java

import android.net.Uri
import android.os.Build
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.install.IntegrityReport
import dev.eloa.launcher.domain.java.install.VersionInstaller
import dev.eloa.launcher.domain.java.loader.FabricLikeProvider
import dev.eloa.launcher.domain.java.loader.ForgeLikeProvider
import dev.eloa.launcher.domain.java.loader.LoaderProvider
import dev.eloa.launcher.domain.java.loader.LoaderVersion
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.meta.RuleEnvironment
import dev.eloa.launcher.domain.java.meta.VersionManifest
import dev.eloa.launcher.domain.java.net.MetaClient
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInspector
import dev.eloa.launcher.di.DownloadManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ManifestState(val manifest: VersionManifest?, val fromCache: Boolean, val error: String?, val loading: Boolean)

@Singleton
class JavaRepository @Inject constructor(
    private val meta: MetaClient,
    private val storage: LauncherStorage,
    private val downloads: DownloadManagerFactory,
    private val logger: AppLogger,
) {
    private val _manifest = MutableStateFlow(ManifestState(null, false, null, false))
    val manifest: StateFlow<ManifestState> = _manifest
    private val mutex = Mutex()
    private val cacheFile get() = File(storage.javaRoot, "cache/version_manifest_v2.json")

    val dirs get() = storage.gameDirectories
    val ruleEnv: RuleEnvironment get() = RuleEnvironment.forAbi(Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")

    /** Loads the official version manifest; falls back to the last cached copy when offline. */
    suspend fun refreshManifest(force: Boolean = false): VersionManifest? = mutex.withLock {
        if (!force) _manifest.value.manifest?.let { if (!_manifest.value.fromCache) return it }
        _manifest.value = _manifest.value.copy(loading = true, error = null)
        withContext(Dispatchers.IO) {
            try {
                val text = meta.getText(VersionManifest.URL)
                val m = VersionManifest.parse(text)
                FileOps.writeAtomically(cacheFile, text.toByteArray())
                _manifest.value = ManifestState(m, false, null, false)
                m
            } catch (e: Exception) {
                logger.w("Versions", "Manifest download failed", e)
                val cached = runCatching { if (cacheFile.isFile) VersionManifest.parse(cacheFile.readText()) else null }.getOrNull()
                _manifest.value = ManifestState(cached, cached != null, e.message ?: "Network error", false)
                cached
            }
        }
    }

    suspend fun installer() = VersionInstaller(meta, downloads.create(), dirs, ruleEnv, logger)

    fun installedVersions(): List<String> = dirs.installedVersionIds()

    fun provider(type: ModLoaderType): LoaderProvider? = when (type) {
        ModLoaderType.FABRIC, ModLoaderType.QUILT -> FabricLikeProvider(type, meta)
        ModLoaderType.FORGE, ModLoaderType.NEOFORGE -> ForgeLikeProvider(type, meta)
        ModLoaderType.VANILLA -> null
    }

    suspend fun loaderVersions(type: ModLoaderType, mc: String): List<LoaderVersion> = provider(type)?.versions(mc).orEmpty()

    suspend fun verify(versionId: String): IntegrityReport {
        val inst = installer()
        val resolved = inst.resolveLocal(versionId)
        val clientId = clientJarIdFor(versionId)
        return inst.verify(resolved, clientId)
    }

    /** The jar id: loader profiles reuse the vanilla client jar of their parent. */
    fun clientJarIdFor(versionId: String): String {
        var id = versionId
        repeat(5) {
            val f = dirs.versionJson(id)
            if (!f.isFile) return id
            val parent = runCatching { dev.eloa.launcher.domain.java.meta.VersionJson.parse(f.readText()).inheritsFrom }.getOrNull() ?: return id
            id = parent
        }
        return id
    }

    suspend fun deleteVersion(versionId: String, usedBy: List<String>) = withContext(Dispatchers.IO) {
        if (usedBy.isNotEmpty()) throw IOException("Version is used by profile(s): ${usedBy.joinToString()}")
        dirs.versionDir(versionId).deleteRecursively()
        logger.i("Versions", "Deleted version $versionId")
    }

    // ---- Java runtimes --------------------------------------------------------------------
    fun runtimes(): List<JavaRuntimeInfo> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return emptyList()
        return dirs.runtimes.listFiles()?.filter { it.isDirectory }?.mapNotNull { runCatching { JavaRuntimeInspector.inspect(it, abi) }.getOrNull() }.orEmpty()
            .sortedByDescending { it.majorVersion }
    }

    /**
     * Runtime for a version needing Java [major]. Automatic choice prefers the exact major version
     * (newer Java can break old Minecraft/Forge), then the closest newer one. A manually chosen
     * runtime ([preferredName]) is used as-is; preflight reports it if it is too old.
     */
    fun runtimeFor(major: Int, preferredName: String? = null): JavaRuntimeInfo? {
        val all = runtimes()
        if (preferredName != null) all.firstOrNull { it.home.name == preferredName }?.let { return it }
        return all.firstOrNull { it.majorVersion == major } ?: all.filter { it.majorVersion >= major }.minByOrNull { it.majorVersion }
    }

    /** Required Java major per Minecraft version (as declared by Mojang in version.json). */
    fun requiredJava(versionId: String): Int? = runCatching {
        dev.eloa.launcher.domain.java.meta.VersionJson.parse(dirs.versionJson(clientJarIdFor(versionId)).readText()).javaVersion?.majorVersion
    }.getOrNull()

    /** Recursively hashes every file of a runtime and compares with the manifest stored at import. */
    fun verifyRuntime(info: JavaRuntimeInfo): Boolean = runCatching {
        val manifest = java.io.File(info.home, ".eloa-files.sha256")
        if (!manifest.isFile) return@runCatching JavaRuntimeInspector.inspect(info.home, Build.SUPPORTED_ABIS.first()).majorVersion == info.majorVersion
        manifest.readLines().filter { it.isNotBlank() }.all { line ->
            val hash = line.substringBefore("  "); val rel = line.substringAfter("  ")
            val f = PathSafety.resolveInside(info.home, rel)
            f.isFile && dev.eloa.launcher.core.common.hash.Hashing.digest(f, dev.eloa.launcher.core.common.hash.HashAlgorithm.SHA256) == hash
        }
    }.getOrDefault(false)

    fun runtimeSize(info: JavaRuntimeInfo): Long = FileOps.sizeOf(info.home)

    private fun writeRuntimeManifest(home: File) {
        val lines = home.walkTopDown().filter { it.isFile && it.name != ".eloa-files.sha256" }.map { f ->
            dev.eloa.launcher.core.common.hash.Hashing.digest(f, dev.eloa.launcher.core.common.hash.HashAlgorithm.SHA256) + "  " + f.relativeTo(home).invariantSeparatorsPath
        }.joinToString("\n")
        File(home, ".eloa-files.sha256").writeText(lines)
    }

    suspend fun importRuntime(uri: Uri): JavaRuntimeInfo = withContext(Dispatchers.IO) {
        val tmp = storage.copyToTemp(uri, 1024L * 1024 * 1024)
        try {
            val abi = Build.SUPPORTED_ABIS.first()
            val name = PathSafety.sanitizeFileName(storage.displayName(uri).substringBeforeLast('.'), "runtime")
            JavaRuntimeInspector.importZip(tmp, PathSafety.resolveInside(dirs.runtimes, name), abi).also {
                writeRuntimeManifest(it.home)
                logger.i("Runtime", "Imported Java ${it.fullVersion} (${it.arch})")
            }
        } finally { tmp.delete() }
    }

    fun deleteRuntime(info: JavaRuntimeInfo) {
        if (info.home.canonicalPath.startsWith(dirs.runtimes.canonicalPath)) info.home.deleteRecursively()
    }
}
