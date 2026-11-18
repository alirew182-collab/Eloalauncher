package dev.eloa.launcher.feature.diagnostics

import androidx.core.content.edit
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.AddonDao
import dev.eloa.launcher.core.data.db.BedrockDao
import dev.eloa.launcher.core.data.db.LogDao
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.performance.PerformanceAdvisor
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.mods.IssueSeverity
import dev.eloa.launcher.domain.java.net.MetaClient
import dev.eloa.launcher.domain.java.meta.VersionManifest
import dev.eloa.launcher.feature.addons.AddonRepository
import dev.eloa.launcher.feature.java.ContentRepository
import dev.eloa.launcher.feature.java.InstallState
import dev.eloa.launcher.feature.java.JavaRepository
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.feature.java.state
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class DiagSeverity { INFO, WARNING, ERROR, CRITICAL }

/** A fix is "safe" when it cannot lose user data; others need explicit confirmation. */
data class AutoFix(val id: String, val label: String, val requiresConfirmation: Boolean, val confirmText: String? = null)

data class DiagnosticIssue(
    val id: String,
    val title: String,
    val cause: String,
    val severity: DiagSeverity,
    val component: String,
    val solution: String,
    val fix: AutoFix? = null,
)

data class DiagnosticReport(val issues: List<DiagnosticIssue>, val checksRun: Int, val durationMs: Long, val timestamp: Long)

@Singleton
class DiagnosticsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: LauncherStorage,
    private val bedrockDao: BedrockDao,
    private val addonDao: AddonDao,
    private val addons: AddonRepository,
    private val profiles: ProfileRepository,
    private val content: ContentRepository,
    private val java: JavaRepository,
    private val device: DeviceInfoProvider,
    private val settings: SettingsRepository,
    private val logDao: LogDao,
    private val meta: MetaClient,
    private val logger: AppLogger,
) {
    suspend fun run(onProgress: (String) -> Unit = {}): DiagnosticReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val issues = ArrayList<DiagnosticIssue>()
        var checks = 0
        suspend fun check(name: String, block: suspend () -> Unit) {
            onProgress(name); checks++
            try { block() } catch (e: Exception) {
                issues += DiagnosticIssue("check-$name", "Check “$name” could not run", e.message ?: e.javaClass.simpleName, DiagSeverity.WARNING, "Diagnostics", "Retry; if it persists, export the log.")
            }
        }

        check("Storage") {
            val free = storage.freeBytes()
            if (free < 300L * 1_048_576) issues += DiagnosticIssue("storage-critical", "Storage almost full", "Only ${FileOps.formatSize(free)} free.", DiagSeverity.CRITICAL, "Storage", "Free space on the device or delete unused versions/backups.", AutoFix("cleanup", "Clean temporary files", false))
            else if (free < 1024L * 1_048_576) issues += DiagnosticIssue("storage-low", "Low storage", "${FileOps.formatSize(free)} free; game installs need 1–2 GB.", DiagSeverity.WARNING, "Storage", "Free some space before installing versions.", AutoFix("cleanup", "Clean temporary files", false))
        }
        check("Directories") {
            listOf(storage.javaRoot, storage.instancesRoot, storage.bedrockApks, storage.bedrockAddons, storage.exports, storage.backups).forEach { d ->
                if (!d.isDirectory || !d.canWrite()) issues += DiagnosticIssue("dir-${d.name}", "Folder not accessible: ${d.name}", "The folder is missing or not writable.", DiagSeverity.ERROR, "File system", "Recreate the folder.", AutoFix("mkdirs", "Recreate folders", false))
            }
        }
        check("Temporary files") {
            val size = FileOps.sizeOf(storage.importTemp) + FileOps.sizeOf(storage.shareDir)
            val partials = storage.javaRoot.walkTopDown().count { it.isFile && it.name.endsWith(".part") }
            if (size > 200L * 1_048_576 || partials > 0) issues += DiagnosticIssue("temp", "Leftover temporary files", "${FileOps.formatSize(size)} in temp, $partials interrupted download(s).", DiagSeverity.INFO, "Storage", "Remove temporary data (interrupted downloads restart from scratch).", AutoFix("cleanup-all", "Remove temporary files", false))
        }
        check("Memory") {
            val info = device.collect(includeGpu = false)
            if (info.lowMemory || info.availRamMb < 700) issues += DiagnosticIssue("ram-low", "Low free memory", "${info.availRamMb} MB free of ${info.totalRamMb} MB.", DiagSeverity.WARNING, "Device", "Close background apps before playing; choose a lower performance preset.")
            if (info.thermalStatus in setOf("Severe", "Critical", "Emergency", "Shutdown")) issues += DiagnosticIssue("thermal", "Device is overheating", "Thermal status: ${info.thermalStatus}.", DiagSeverity.WARNING, "Device", "Let the device cool down; the system will throttle performance.")
            val safe = PerformanceAdvisor.safeMaxMemoryMb(info.totalRamMb)
            profiles.all().filter { it.maxMemoryMb > safe }.forEach { p ->
                issues += DiagnosticIssue("mem-${p.id}", "Too much memory for “${p.name}”", "${p.maxMemoryMb} MB allocated; safe limit on this device is $safe MB.", DiagSeverity.WARNING, "Java profile", "Lower the allocation to $safe MB.",
                    AutoFix("mem:${p.id}:$safe", "Set to $safe MB", true, "Change max memory of “${p.name}” from ${p.maxMemoryMb} MB to $safe MB?"))
            }
        }
        check("Network") {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                issues += DiagnosticIssue("net-off", "No network connection", "The device is offline.", DiagSeverity.WARNING, "Network", "Connect to the internet to download versions or sign in.")
            } else {
                val ok = withTimeoutOrNull(8_000) { runCatching { VersionManifest.parse(meta.getText(VersionManifest.URL)) }.isSuccess } ?: false
                if (!ok) issues += DiagnosticIssue("net-mojang", "Cannot reach Minecraft servers", "piston-meta.mojang.com did not answer correctly.", DiagSeverity.WARNING, "Network", "Check DNS/VPN/firewall; try again later.")
            }
        }
        check("Bedrock library") {
            bedrockDao.all().forEach { e ->
                val missing = e.apkFiles.split(',').filter { it.isNotBlank() }.map { File(e.storageDir, it) }.filter { !it.isFile }
                if (missing.isNotEmpty()) issues += DiagnosticIssue("bed-missing-${e.id}", "Missing APK files: ${e.displayName}", "${missing.size} stored file(s) were removed.", DiagSeverity.ERROR, "Bedrock", "Import the APK again or remove the library entry.")
                if (!e.integrityOk) issues += DiagnosticIssue("bed-integrity-${e.id}", "Damaged APK: ${e.displayName}", "Last verification failed.", DiagSeverity.ERROR, "Bedrock", "Re-import the APK from its original source.")
                if (!e.signatureValid) issues += DiagnosticIssue("bed-sig-${e.id}", "Invalid signature: ${e.displayName}", "Android will refuse to install it.", DiagSeverity.ERROR, "Bedrock", "Use an unmodified, properly signed APK.")
                if (e.compatStatus == "INCOMPATIBLE") issues += DiagnosticIssue("bed-compat-${e.id}", "Incompatible APK: ${e.displayName}", e.compatReasons, DiagSeverity.WARNING, "Bedrock", "Import a build for this device's architecture/Android version.")
            }
        }
        check("Add-ons") {
            val all = addonDao.all()
            all.forEach { a ->
                if (!File(a.storedPath).isFile) issues += DiagnosticIssue("addon-missing-${a.id}", "Add-on file missing: ${a.name}", "Stored file was removed.", DiagSeverity.ERROR, "Add-ons", "Re-import or delete the entry.")
                else {
                    val c = addons.conflicts(a.id).filter { it.severity == dev.eloa.launcher.domain.bedrock.apk.Severity.ERROR }
                    if (c.isNotEmpty()) issues += DiagnosticIssue("addon-dep-${a.id}", "Add-on problem: ${a.name}", c.joinToString("\n") { it.message }, DiagSeverity.WARNING, "Add-ons", "Import the missing dependencies or a compatible version.")
                }
            }
        }
        check("Java profiles") {
            val runtimes = java.runtimes()
            for (p in profiles.all()) {
                when (p.state) {
                    InstallState.FAILED -> issues += DiagnosticIssue("prof-fail-${p.id}", "Installation failed: ${p.name}", p.lastError ?: "Unknown error", DiagSeverity.ERROR, "Java profile", "Retry the installation.", AutoFix("reinstall:${p.id}", "Retry install", false))
                    InstallState.NEEDS_PROCESSORS -> issues += DiagnosticIssue("prof-proc-${p.id}", "Loader setup incomplete: ${p.name}", "${p.pendingProcessors} Forge/NeoForge processor step(s) need a Java runtime.", DiagSeverity.WARNING, "Mod loader", "Install a Java runtime; the steps run before the first launch.")
                    InstallState.INSTALLED -> {
                        val id = p.launchVersionId
                        if (id == null || !java.dirs.versionJson(id).isFile) issues += DiagnosticIssue("prof-ver-${p.id}", "Version files missing: ${p.name}", "versions/$id is missing.", DiagSeverity.ERROR, "Java profile", "Reinstall the profile.", AutoFix("reinstall:${p.id}", "Reinstall", false))
                        else {
                            val report = java.verify(id)
                            if (!report.ok) issues += DiagnosticIssue("prof-int-${p.id}", "Damaged game files: ${p.name}", "${report.missing.size} missing, ${report.corrupted.size} corrupted of ${report.checked}.", DiagSeverity.ERROR, "Game files", "Repair downloads only the affected files.", AutoFix("reinstall:${p.id}", "Repair", false))
                        }
                    }
                    else -> Unit
                }
                if (!storage.instance(p.gameDirName).gameDir.isDirectory) issues += DiagnosticIssue("prof-dir-${p.id}", "Game folder missing: ${p.name}", "The profile directory does not exist.", DiagSeverity.ERROR, "Java profile", "Recreate the folder.", AutoFix("profdir:${p.id}", "Recreate folder", false))
                val mods = content.mods(p)
                val modIssues = content.checkMods(p, mods).filter { it.severity == IssueSeverity.ERROR }
                if (modIssues.isNotEmpty()) {
                    val bad = modIssues.mapNotNull { it.modFile }.flatMap { it.split(", ") }.distinct()
                    issues += DiagnosticIssue("mods-${p.id}", "Mod problems in “${p.name}”", modIssues.take(6).joinToString("\n") { "• ${it.message}" }, DiagSeverity.ERROR, "Mods", modIssues.firstNotNullOfOrNull { it.suggestion } ?: "Fix or disable the listed mods.",
                        AutoFix("disablemods:${p.id}:${bad.joinToString("|")}", "Disable ${bad.size} mod(s)", true, "Disable these mods in “${p.name}”?\n${bad.joinToString("\n")}"))
                }
            }
            if (runtimes.isEmpty() && profiles.all().isNotEmpty()) issues += DiagnosticIssue("runtime-none", "No Java runtime installed", "Java Edition needs a JVM built for Android.", DiagSeverity.INFO, "Java runtime", "Import a runtime in Settings → Java runtimes.")
        }
        check("Accounts and settings") {
            val s = settings.current()
            if (s.msClientId.isBlank()) issues += DiagnosticIssue("auth-client", "Microsoft sign-in not configured", "No Azure client ID is set.", DiagSeverity.INFO, "Accounts", "Enter a client ID approved for the Minecraft API in Settings → Accounts.")
        }
        check("Errors and crashes") {
            val errors = logDao.observeErrorCount(System.currentTimeMillis() - 86_400_000).first()
            if (errors > 0) issues += DiagnosticIssue("log-errors", "$errors error(s) in the last 24 h", "See the error log for details.", DiagSeverity.INFO, "Logs", "Open the error log to inspect them.")
            val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
            val crash = prefs.getLong("last_crash", 0)
            if (crash > 0) issues += DiagnosticIssue("crash", "The launcher crashed previously", "${prefs.getString("last_crash_type", "Unknown")} at ${dev.eloa.launcher.feature.common.formatDate(crash)}. Data was preserved.", DiagSeverity.WARNING, "Launcher", "Export logs if it happens again.", AutoFix("clearcrash", "Dismiss", false))
        }
        val order = issues.sortedByDescending { it.severity.ordinal }
        logger.i("Diagnostics", "Diagnostics finished", mapOf("issues" to order.size.toString(), "checks" to checks.toString()))
        DiagnosticReport(order, checks, System.currentTimeMillis() - start, System.currentTimeMillis())
    }

    /** Applies a fix. Fixes flagged requiresConfirmation must only be called after user consent. */
    suspend fun applyFix(fixId: String): String = withContext(Dispatchers.IO) {
        val parts = fixId.split(':')
        val msg = when (parts[0]) {
            "cleanup" -> "Freed ${FileOps.formatSize(storage.cleanupTemp(3_600_000))}"
            "cleanup-all" -> "Freed ${FileOps.formatSize(storage.cleanupTemp(0))}"
            "mkdirs" -> { storage.ensureBaseDirs(); "Folders recreated" }
            "mem" -> { profiles.setMemory(parts[1], parts[2].toInt()); "Memory set to ${parts[2]} MB" }
            "reinstall" -> { dev.eloa.launcher.feature.java.InstallWorker.enqueue(context, parts[1]); "Installation queued" }
            "profdir" -> { profiles.get(parts[1])?.let { storage.instance(it.gameDirName).ensure() }; "Folder recreated" }
            "disablemods" -> {
                val p = profiles.get(parts[1]) ?: throw IllegalStateException("Profile not found")
                val mm = content.modManager(p)
                val names = parts.drop(2).joinToString(":").split('|').filter { it.isNotBlank() }
                names.forEach { runCatching { mm.setEnabled(it, false) } }
                "Disabled ${names.size} mod(s)"
            }
            "clearcrash" -> { context.getSharedPreferences("crash", Context.MODE_PRIVATE).edit { clear() }; "Dismissed" }
            else -> throw IllegalArgumentException("Unknown fix")
        }
        logger.i("Diagnostics", "Applied fix ${parts[0]}", mapOf("result" to msg))
        msg
    }
}
