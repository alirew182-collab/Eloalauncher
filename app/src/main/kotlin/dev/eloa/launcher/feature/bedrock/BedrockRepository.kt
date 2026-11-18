package dev.eloa.launcher.feature.bedrock

import android.net.Uri
import android.os.Build
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.BedrockDao
import dev.eloa.launcher.core.data.db.BedrockVersionEntity
import dev.eloa.launcher.core.data.db.ImportHistoryEntity
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.bedrock.apk.ApkInfo
import dev.eloa.launcher.domain.bedrock.apk.ApkInspection
import dev.eloa.launcher.domain.bedrock.apk.ApkInspector
import dev.eloa.launcher.domain.bedrock.apk.ApkSetAssembler
import dev.eloa.launcher.domain.bedrock.apk.ApkSetResult
import dev.eloa.launcher.domain.bedrock.apk.CompatibilityChecker
import dev.eloa.launcher.domain.bedrock.apk.DeviceSpec
import dev.eloa.launcher.domain.bedrock.apk.InstallDecision
import dev.eloa.launcher.domain.bedrock.apk.InstallPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ImportReport(val imported: List<String>, val duplicates: List<String>, val failures: List<Pair<String, String>>, val warnings: List<String>)

data class InstallPlan(val entity: BedrockVersionEntity, val decision: InstallDecision, val explanation: String, val canProceed: Boolean)

@Serializable
private data class BedrockMetadataBackup(
    val name: String, val packageName: String, val versionName: String?, val versionCode: Long, val abis: String,
    val sha256: String, val certSha256: String, val importedAt: Long, val notes: String,
)

@Singleton
class BedrockRepository @Inject constructor(
    private val dao: BedrockDao,
    private val storage: LauncherStorage,
    private val installer: PackageInstallerGateway,
    private val logger: AppLogger,
) {
    val versions: Flow<List<BedrockVersionEntity>> = dao.observeAll()
    val history: Flow<List<ImportHistoryEntity>> = dao.observeHistory()

    fun deviceSpec() = DeviceSpec(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

    /**
     * Imports APK / APKS / XAPK files chosen through SAF. Originals are only read, never changed.
     * Files are copied into private storage only after inspection and grouping succeed.
     */
    suspend fun import(uris: List<Uri>, onProgress: (String, Float) -> Unit): ImportReport = withContext(Dispatchers.IO) {
        val inspector = ApkInspector()
        val work = File(storage.importTemp, "apk-${System.nanoTime()}").apply { mkdirs() }
        val infos = ArrayList<Pair<ApkInfo, File>>()
        val failures = ArrayList<Pair<String, String>>()
        val warnings = ArrayList<String>()
        val imported = ArrayList<String>()
        val duplicates = ArrayList<String>()
        try {
            uris.forEachIndexed { i, uri ->
                val name = storage.displayName(uri)
                try {
                    onProgress("Copying $name", i.toFloat() / uris.size)
                    val tmp = storage.copyToTemp(uri, MAX_APK_BYTES)
                    val files = if (ApkSetAssembler.isBundle(File(name))) {
                        try { ApkSetAssembler.extractBundle(tmp, File(work, "b$i")) } finally { tmp.delete() }
                    } else listOf(tmp)
                    for (f in files) {
                        onProgress("Verifying ${f.name.substringAfter('_')}", i.toFloat() / uris.size)
                        when (val r = inspector.inspect(f) { p -> onProgress("Verifying $name", (i + p) / uris.size) }) {
                            is ApkInspection.Ok -> {
                                infos += r.info.copy(fileName = if (f == tmp) name else f.name) to f
                                r.findings.forEach { warnings += "$name: ${it.message}" }
                            }
                            is ApkInspection.Failed -> { failures += name to r.reason; f.delete() }
                        }
                    }
                } catch (e: Exception) {
                    failures += name to (e.message ?: e.javaClass.simpleName)
                    logger.w("Bedrock", "Import failed for $name", e)
                }
            }
            val device = deviceSpec()
            val byInfo = infos.associate { it.first.sha256 to it.second }
            for (result in ApkSetAssembler.group(infos.map { it.first })) {
                when (result) {
                    is ApkSetResult.Failed -> failures += "APK set" to result.reason
                    is ApkSetResult.Ok -> {
                        val set = result.set
                        val label = "${set.base.versionName ?: set.versionCode}"
                        if (dao.bySha(set.base.sha256) != null) { duplicates += label; continue }
                        if (!set.base.isMinecraft) warnings += "${set.packageName} is not Minecraft Bedrock; it was imported as a generic package."
                        val compat = CompatibilityChecker.check(set, device)
                        val dir = PathSafety.resolveInside(storage.bedrockApks, "${PathSafety.sanitizeFileName(set.packageName)}-${set.versionCode}-${set.base.sha256.take(8)}")
                        dir.mkdirs()
                        val stored = set.all.map { apk ->
                            val target = File(dir, if (apk.isBase) "base.apk" else "split_${PathSafety.sanitizeFileName(apk.splitName ?: "x")}.apk")
                            FileOps.move(byInfo.getValue(apk.sha256), target, overwrite = true)
                            target.name
                        }
                        dao.insert(BedrockVersionEntity(
                            displayName = if (set.base.isMinecraft) "Minecraft ${set.base.versionName ?: set.versionCode}" else set.packageName,
                            packageName = set.packageName, versionName = set.base.versionName, versionCode = set.versionCode,
                            abis = set.abis.joinToString(","), minSdk = set.all.maxOf { it.minSdk }, targetSdk = set.base.targetSdk,
                            totalSize = set.totalSize, baseSha256 = set.base.sha256,
                            certSha256 = set.base.signature.certificateSha256.joinToString(","),
                            signatureValid = set.all.all { it.signature.verified },
                            compatStatus = compat.status.name, compatReasons = compat.reasons.joinToString("\n"),
                            storageDir = dir.absolutePath, apkFiles = stored.joinToString(","), importedAt = System.currentTimeMillis(),
                            lastVerifiedAt = System.currentTimeMillis(),
                        ))
                        imported += label
                    }
                }
            }
            val now = System.currentTimeMillis()
            imported.forEach { dao.insertHistory(ImportHistoryEntity(kind = "APK", fileName = it, timestamp = now, success = true, message = "Imported")) }
            duplicates.forEach { dao.insertHistory(ImportHistoryEntity(kind = "APK", fileName = it, timestamp = now, success = false, message = "Duplicate — already in library")) }
            failures.forEach { (n, m) -> dao.insertHistory(ImportHistoryEntity(kind = "APK", fileName = n, timestamp = now, success = false, message = m)) }
            logger.i("Bedrock", "Import finished", mapOf("imported" to imported.size.toString(), "failed" to failures.size.toString()))
            ImportReport(imported, duplicates, failures, warnings.distinct())
        } finally {
            work.deleteRecursively()
            infos.forEach { (_, f) -> if (f.parentFile == storage.importTemp) f.delete() }
        }
    }

    fun files(e: BedrockVersionEntity): List<File> = e.apkFiles.split(',').filter { it.isNotBlank() }.map { File(e.storageDir, it) }

    suspend fun rename(id: Long, name: String) { dao.byId(id)?.let { dao.update(it.copy(displayName = name.trim().take(80))) } }

    suspend fun setNotes(id: Long, notes: String) { dao.byId(id)?.let { dao.update(it.copy(notes = notes.take(500))) } }

    /** Removes the library record; files are deleted only when [deleteFiles] (user confirmed). */
    suspend fun delete(id: Long, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: return@withContext
        dao.delete(e)
        if (deleteFiles) File(e.storageDir).let { dir -> if (dir.canonicalPath.startsWith(storage.bedrockApks.canonicalPath)) dir.deleteRecursively() }
        logger.i("Bedrock", "Library entry removed", mapOf("name" to e.displayName, "filesDeleted" to deleteFiles.toString()))
    }

    /** Re-checks hashes, CRCs and signatures of stored files. */
    suspend fun verify(id: Long, onProgress: (Float) -> Unit = {}): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: return@withContext false to "Not found"
        val files = files(e)
        val missing = files.filter { !it.isFile }
        val problems = ArrayList<String>()
        if (missing.isNotEmpty()) problems += "Missing: ${missing.joinToString { it.name }}"
        val inspector = ApkInspector()
        files.filter { it.isFile }.forEachIndexed { i, f ->
            when (val r = inspector.inspect(f) { p -> onProgress((i + p) / files.size) }) {
                is ApkInspection.Failed -> problems += "${f.name}: ${r.reason}"
                is ApkInspection.Ok -> {
                    if (f.name == "base.apk" && r.info.sha256 != e.baseSha256) problems += "base.apk content changed"
                    if (!r.info.signature.verified) problems += "${f.name}: invalid signature"
                }
            }
        }
        val ok = problems.isEmpty()
        dao.update(e.copy(integrityOk = ok, lastVerifiedAt = System.currentTimeMillis()))
        ok to (if (ok) "All ${files.size} file(s) are intact and correctly signed." else problems.joinToString("\n"))
    }

    suspend fun planInstall(id: Long): InstallPlan? {
        val e = dao.byId(id) ?: return null
        val inspector = ApkInspector(verifyEntries = false)
        val infos = files(e).mapNotNull { (inspector.inspect(it) as? ApkInspection.Ok)?.info }
        val set = (ApkSetAssembler.assemble(infos) as? ApkSetResult.Ok)?.set
            ?: return InstallPlan(e, InstallDecision.Blocked("Stored files are incomplete or damaged — run verification."), "Stored files are incomplete or damaged — run verification.", false)
        val decision = InstallPlanner.decide(set, installer.installedPackage(e.packageName), CompatibilityChecker.check(set, deviceSpec()))
        val ok = decision is InstallDecision.FreshInstall || decision is InstallDecision.Update || decision is InstallDecision.Reinstall
        return InstallPlan(e, decision, InstallPlanner.explain(decision), ok)
    }

    suspend fun install(id: Long, onProgress: (Float) -> Unit): Int {
        val plan = planInstall(id) ?: throw IOException("Version not found")
        if (!plan.canProceed) throw IOException(plan.explanation)
        if (!installer.canRequestInstalls()) throw InstallPermissionMissing()
        logger.i("Bedrock", "Starting installation", mapOf("version" to plan.entity.displayName))
        return installer.install(files(plan.entity), plan.entity.packageName, onProgress)
    }

    suspend fun markLaunched(id: Long) { dao.byId(id)?.let { dao.update(it.copy(lastLaunchedAt = System.currentTimeMillis())) } }

    /** Exports library metadata (no APK content) as JSON to a SAF document. */
    suspend fun exportMetadata(uri: Uri) = withContext(Dispatchers.IO) {
        val list = dao.all().map { BedrockMetadataBackup(it.displayName, it.packageName, it.versionName, it.versionCode, it.abis, it.baseSha256, it.certSha256, it.importedAt, it.notes) }
        val tmp = File(storage.importTemp, "bedrock-library.json").apply { parentFile?.mkdirs() }
        tmp.writeText(PrettyJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(BedrockMetadataBackup.serializer()), list))
        try { storage.exportTo(tmp, uri) } finally { tmp.delete() }
    }

    companion object { const val MAX_APK_BYTES = 4L * 1024 * 1024 * 1024 }
}

private val PrettyJson = Json { prettyPrint = true }

class InstallPermissionMissing : IOException("Allow this launcher to install apps (Settings → Install unknown apps), then try again.")
