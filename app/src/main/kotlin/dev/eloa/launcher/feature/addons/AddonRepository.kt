package dev.eloa.launcher.feature.addons

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.AddonDao
import dev.eloa.launcher.core.data.db.AddonEntity
import dev.eloa.launcher.core.data.db.ImportHistoryEntity
import dev.eloa.launcher.core.data.db.BedrockDao
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.bedrock.addon.AddonInspector
import dev.eloa.launcher.domain.bedrock.addon.AddonKind
import dev.eloa.launcher.domain.bedrock.addon.AddonLibraryChecker
import dev.eloa.launcher.domain.bedrock.addon.LibraryPack
import dev.eloa.launcher.domain.bedrock.addon.PackDependency
import dev.eloa.launcher.domain.bedrock.addon.PackManifest
import dev.eloa.launcher.domain.bedrock.addon.PackType
import dev.eloa.launcher.domain.bedrock.apk.Finding
import dev.eloa.launcher.domain.bedrock.apk.KnownPackages
import dev.eloa.launcher.domain.bedrock.apk.Severity
import dev.eloa.launcher.feature.common.SafTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PackSummary(
    val uuid: String, val version: String, val name: String, val type: String, val minEngine: String?,
    val dependencies: List<String>, val moduleUuids: List<String>, val rootPath: String,
)

data class AddonImportResult(val name: String, val ok: Boolean, val message: String, val findings: List<Finding>)

private val json = Json { ignoreUnknownKeys = true }

fun AddonEntity.packs(): List<PackSummary> = runCatching { json.decodeFromString(ListSerializer(PackSummary.serializer()), packsJson) }.getOrDefault(emptyList())

@Singleton
class AddonRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AddonDao,
    private val bedrockDao: BedrockDao,
    private val storage: LauncherStorage,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
) {
    val addons: Flow<List<AddonEntity>> = dao.observeAll()
    fun recent(limit: Int) = dao.observeRecent(limit)

    private suspend fun selectedGameVersion(): String? {
        val id = settings.current().selectedBedrockId
        return (if (id > 0) bedrockDao.byId(id) else bedrockDao.all().firstOrNull())?.versionName?.split('.')?.take(3)?.joinToString(".")
    }

    private fun libraryPacks(all: List<AddonEntity>, excludeId: Long? = null): List<LibraryPack> =
        all.filter { it.id != excludeId }.flatMap { a -> a.packs().map { LibraryPack(it.uuid, it.version, it.name, a.enabled) } }

    suspend fun import(uris: List<Uri>, onProgress: (String, Float) -> Unit): List<AddonImportResult> = withContext(Dispatchers.IO) {
        val inspector = AddonInspector()
        uris.mapIndexed { i, uri ->
            val name = storage.displayName(uri)
            onProgress(name, i.toFloat() / uris.size)
            var tmp: File? = null
            try {
                if (inspector.kindOf(File(name)) == null) return@mapIndexed AddonImportResult(name, false, "Unsupported type. Use .mcpack, .mcaddon, .mcworld, .mctemplate, .mcstructure or .zip", emptyList())
                tmp = storage.copyToTemp(uri, MAX_ADDON_BYTES)
                val renamed = File(tmp.parentFile, "x_" + PathSafety.sanitizeFileName(name)).also { tmp.renameTo(it); tmp = it }
                val analysis = inspector.inspect(renamed)
                val sha = Hashing.digest(renamed, HashAlgorithm.SHA256)
                if (dao.bySha(sha) != null) {
                    record(name, false, "Duplicate — identical file already in the library")
                    return@mapIndexed AddonImportResult(name, false, "Already in the library", emptyList())
                }
                if (analysis.packs.isEmpty() && analysis.world == null && analysis.structure == null) {
                    val reason = analysis.findings.firstOrNull { it.severity == Severity.ERROR }?.message ?: "No content found"
                    record(name, false, reason)
                    return@mapIndexed AddonImportResult(name, false, reason, analysis.findings)
                }
                val manifests = analysis.packs.map { it.manifest }
                val cross = AddonLibraryChecker.check(manifests, libraryPacks(dao.all()), selectedGameVersion())
                val findings = analysis.findings + cross
                val target = File(storage.bedrockAddons, "${System.currentTimeMillis()}_${PathSafety.sanitizeFileName(name)}")
                FileOps.move(renamed, target)
                tmp = null
                val summaries = analysis.packs.map { p ->
                    PackSummary(p.manifest.uuid, p.manifest.version, p.manifest.name, p.manifest.type.name, p.manifest.minEngineVersion,
                        p.manifest.dependencies.map { d -> (d.uuid ?: d.moduleName ?: "?") + "@" + d.version }, p.manifest.moduleUuids, p.rootPath)
                }
                val first = manifests.firstOrNull()
                dao.insert(AddonEntity(
                    name = analysis.displayName, description = first?.description ?: analysis.world?.let { "World · ${it.embeddedPacks} embedded pack(s)" } ?: analysis.structure?.let { "Structure ${it.sizeX}×${it.sizeY}×${it.sizeZ}" } ?: "",
                    kind = analysis.kind.name, packTypes = manifests.map { it.type.name }.distinct().joinToString(",").ifEmpty { if (analysis.world != null) "WORLD" else "STRUCTURE" },
                    primaryUuid = first?.uuid, packsJson = json.encodeToString(ListSerializer(PackSummary.serializer()), summaries),
                    version = first?.version, minEngineVersion = first?.minEngineVersion, fileName = name, storedPath = target.absolutePath,
                    sizeBytes = target.length(), sha256 = sha, importedAt = System.currentTimeMillis(),
                    category = defaultCategory(analysis.kind, manifests), findings = findings.joinToString("\n") { "${it.severity}: ${it.message}" },
                    hasErrors = findings.any { it.severity == Severity.ERROR },
                ))
                record(name, true, if (findings.any { it.severity == Severity.ERROR }) "Imported with errors" else "Imported")
                AddonImportResult(name, true, "Imported ${analysis.displayName}", findings)
            } catch (e: Exception) {
                logger.w("Addons", "Import failed: $name", e)
                record(name, false, e.message ?: "Error")
                AddonImportResult(name, false, e.message ?: "Import failed", emptyList())
            } finally {
                tmp?.delete()
            }
        }
    }

    private fun defaultCategory(kind: AddonKind, m: List<PackManifest>) = when {
        kind == AddonKind.MCWORLD || kind == AddonKind.MCTEMPLATE -> "Worlds"
        kind == AddonKind.MCSTRUCTURE -> "Structures"
        m.any { it.type == PackType.SKIN } -> "Skins"
        m.all { it.type == PackType.RESOURCE } -> "Texture packs"
        else -> "Add-ons"
    }

    private suspend fun record(name: String, ok: Boolean, msg: String) =
        bedrockDao.insertHistory(ImportHistoryEntity(kind = "ADDON", fileName = name, timestamp = System.currentTimeMillis(), success = ok, message = msg))

    suspend fun setEnabled(id: Long, enabled: Boolean) { dao.byId(id)?.let { dao.update(it.copy(enabled = enabled)) } }
    suspend fun setCategory(id: Long, category: String) { dao.byId(id)?.let { dao.update(it.copy(category = category.trim().take(40))) } }
    suspend fun rename(id: Long, name: String) { dao.byId(id)?.let { dao.update(it.copy(name = name.trim().take(80))) } }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: return@withContext
        dao.delete(e)
        File(e.storedPath).takeIf { it.canonicalPath.startsWith(storage.bedrockAddons.canonicalPath) }?.delete()
        logger.i("Addons", "Deleted ${e.name}")
    }

    /** Lists archive entries for the "view contents" sheet. */
    suspend fun contents(id: Long): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: return@withContext emptyList()
        val f = File(e.storedPath)
        if (!SafeZip.isZip(f)) return@withContext listOf(e.fileName to f.length())
        SafeZip.open(f).use { z -> z.entries.filter { !it.isDirectory }.map { it.name to it.size } }
    }

    /** Re-runs dependency/conflict checks of one add-on against the current library. */
    suspend fun conflicts(id: Long): List<Finding> {
        val all = dao.all()
        val e = all.firstOrNull { it.id == id } ?: return emptyList()
        val manifests = e.packs().map { p ->
            PackManifest(2, p.name, "", p.uuid, p.version, p.minEngine, runCatching { PackType.valueOf(p.type) }.getOrDefault(PackType.UNKNOWN),
                emptyList(), p.moduleUuids, p.dependencies.map { d ->
                    val key = d.substringBeforeLast('@'); val ver = d.substringAfterLast('@')
                    if (dev.eloa.launcher.domain.bedrock.addon.ManifestParser.isValidUuid(key)) PackDependency(key, null, ver) else PackDependency(null, key, ver)
                }, emptyList())
        }
        return AddonLibraryChecker.check(manifests, libraryPacks(all, excludeId = id), selectedGameVersion())
    }

    /** Hands the file to Minecraft via the official VIEW intent; the game imports it itself. */
    suspend fun openInMinecraftIntent(id: Long): Intent = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: throw IOException("Not found")
        val share = File(storage.shareDir, PathSafety.sanitizeFileName(e.fileName)).apply { parentFile?.mkdirs() }
        File(e.storedPath).copyTo(share, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", share)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
            .setPackage(KnownPackages.MINECRAFT_BEDROCK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Copies packs/worlds into a user-granted `games/com.mojang` folder (SAF). Behavior packs go to
     * behavior_packs/, resource packs to resource_packs/, worlds to minecraftWorlds/.
     */
    suspend fun copyToMojangFolder(id: Long): String = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: throw IOException("Not found")
        val uriStr = settings.current().mojangFolderUri.ifEmpty { throw IOException("Choose the games/com.mojang folder in Add-ons → Settings first") }
        val tree = SafTree(context.contentResolver, Uri.parse(uriStr))
        val file = File(e.storedPath)
        var written = 0
        SafeZip.open(file).use { zip ->
            if (e.kind == AddonKind.MCWORLD.name || e.kind == AddonKind.MCTEMPLATE.name) {
                val folder = PathSafety.sanitizeFileName(e.name, "world") + "_" + e.id
                val base = if (e.kind == AddonKind.MCWORLD.name) "minecraftWorlds/$folder" else "world_templates/$folder"
                written += copyEntries(zip, tree, "", base)
            } else {
                for (p in e.packs()) {
                    if (p.rootPath.contains("!/")) continue // nested packs: use "Open in Minecraft"
                    val dir = if (p.type == PackType.RESOURCE.name) "resource_packs" else if (p.type == PackType.SKIN.name) "skin_packs" else "behavior_packs"
                    written += copyEntries(zip, tree, p.rootPath, "$dir/${PathSafety.sanitizeFileName(p.name, "pack")}_${p.uuid.take(8)}")
                }
            }
        }
        if (written == 0) throw IOException("Nothing could be copied; use \"Open in Minecraft\" for this add-on.")
        logger.i("Addons", "Copied ${e.name} to com.mojang", mapOf("files" to written.toString()))
        "Copied $written file(s) to com.mojang"
    }

    private fun copyEntries(zip: SafeZip, tree: SafTree, prefix: String, targetDir: String): Int {
        var n = 0
        val cache = HashMap<String, Uri>()
        for (entry in zip.entries.filter { !it.isDirectory && it.name.startsWith(prefix) }) {
            val rel = entry.name.removePrefix(prefix)
            PathSafety.resolveInside(File("/x"), rel) // validates traversal
            val parts = rel.split('/')
            val dirPath = (targetDir.split('/') + parts.dropLast(1)).joinToString("/")
            val dirUri = cache.getOrPut(dirPath) { tree.dirPath(dirPath) }
            tree.createFile(dirUri, parts.last()).use { out -> zip.openStream(entry.name) { it.copyTo(out) } }
            n++
        }
        return n
    }

    /** Creates a ZIP with all add-on files plus a metadata index and writes it to [uri]. */
    suspend fun backup(uri: Uri) = withContext(Dispatchers.IO) {
        val all = dao.all()
        val tmp = File(storage.importTemp, "addons-backup.zip").apply { parentFile?.mkdirs() }
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("index.json"))
            z.write(json.encodeToString(ListSerializer(BackupEntry.serializer()), all.map { BackupEntry(it.name, it.fileName, it.category, it.enabled, it.sha256) }).toByteArray())
            z.closeEntry()
            all.forEach { a ->
                val f = File(a.storedPath)
                if (f.isFile) { z.putNextEntry(ZipEntry("files/${a.id}_${PathSafety.sanitizeFileName(a.fileName)}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
        }
        try { storage.exportTo(tmp, uri) } finally { tmp.delete() }
        all.size
    }

    @Serializable private data class BackupEntry(val name: String, val fileName: String, val category: String, val enabled: Boolean, val sha256: String)

    companion object { const val MAX_ADDON_BYTES = 2L * 1024 * 1024 * 1024 }
}
