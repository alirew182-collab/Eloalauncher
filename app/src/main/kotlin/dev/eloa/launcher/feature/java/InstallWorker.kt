package dev.eloa.launcher.feature.java

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.eloa.launcher.EloaApp
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.download.DownloadManager
import dev.eloa.launcher.domain.java.download.DownloadProgress
import dev.eloa.launcher.domain.java.download.DownloadRequest
import dev.eloa.launcher.domain.java.install.InstallStage
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.meta.VersionResolver
import dev.eloa.launcher.domain.java.modpack.Mrpack
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Installs everything a profile needs (vanilla files, loader profile, libraries, assets and —
 * for Modrinth packs — mod files) as a foreground job that survives leaving the app.
 */
@HiltWorker
class InstallWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val java: JavaRepository,
    private val profiles: ProfileRepository,
    private val storage: LauncherStorage,
    private val http: OkHttpClient,
    private val logger: AppLogger,
    private val notifications: dev.eloa.launcher.feature.notifications.NotificationCenter,
) : CoroutineWorker(ctx, params) {

    private var lastNotify = 0L
    private var stage = "Preparing"

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE) ?: return Result.failure()
        val mrpack = inputData.getString(KEY_MRPACK)
        val profile = profiles.get(profileId) ?: return Result.failure(workDataOf(KEY_ERROR to "Profile not found"))
        runCatching { setForeground(foreground(0f, "Preparing ${profile.name}")) }
        profiles.setInstallState(profileId, InstallState.INSTALLING)
        return try {
            stage("Loading version list")
            val manifest = java.refreshManifest() ?: throw IOException("Version list unavailable — check your connection")
            val entry = manifest.versions.firstOrNull { it.id == profile.minecraftVersion }
                ?: throw IOException("Minecraft ${profile.minecraftVersion} is not in the official version list")
            val installer = java.installer()
            stage("Minecraft ${entry.id}")
            installer.installVanilla(entry, onStage = { stage(stageLabel(it)) }, onProgress = ::onProgress)

            var launchId = entry.id
            var pending = 0
            val loader = profile.loaderType
            if (loader != ModLoaderType.VANILLA) {
                val lv = profile.loaderVersion ?: java.loaderVersions(loader, entry.id).firstOrNull { it.stable }?.version
                    ?: throw IOException("No ${loader.displayName} version available for ${entry.id}")
                stage("${loader.displayName} $lv")
                val lp = java.provider(loader)!!.install(entry.id, lv, java.dirs)
                val resolved = VersionResolver.resolve(lp.profile) { id -> installer.loadLocal(id) }
                installer.installResolved(resolved, entry.id, onStage = { stage(stageLabel(it)) }, onProgress = ::onProgress)
                launchId = lp.versionId
                pending = lp.pendingProcessors
            }
            if (mrpack != null) installModpackFiles(File(mrpack), profile.gameDirName)
            profiles.setInstalled(profileId, launchId, pending)
            logger.i("Install", "Profile ready: ${profile.name}", mapOf("version" to launchId))
            notifications.post(dev.eloa.launcher.feature.notifications.NoticeType.DOWNLOAD_DONE, R.string.notice_install_done_title,
                applicationContext.getString(R.string.notice_install_done_body, profile.name), "java")
            Result.success()
        } catch (e: CancellationException) {
            profiles.setInstallState(profileId, InstallState.NOT_INSTALLED, "Cancelled")
            throw e
        } catch (e: Exception) {
            logger.e("Install", "Installation failed for ${profile.name}", e)
            profiles.setInstallState(profileId, InstallState.FAILED, e.userMessage())
            if (e is dev.eloa.launcher.domain.java.download.InsufficientSpaceException) {
                notifications.post(dev.eloa.launcher.feature.notifications.NoticeType.LOW_STORAGE, R.string.notice_low_storage_title,
                    applicationContext.getString(R.string.notice_low_storage_body, FileOps.formatSize(storage.freeBytes())), "files")
            }
            notifications.post(dev.eloa.launcher.feature.notifications.NoticeType.INSTALL_FAILED, R.string.notice_install_failed_title, "${profile.name}: ${e.userMessage()}", "downloads")
            Result.failure(workDataOf(KEY_ERROR to e.userMessage()))
        } finally {
            mrpack?.let { File(it).delete() }
        }
    }

    private suspend fun installModpackFiles(pack: File, gameDirName: String) {
        stage("Modpack files")
        val plan = Mrpack.read(pack)
        val gameDir = storage.instance(gameDirName).gameDir
        val requests = plan.files.map { f ->
            DownloadRequest(f.downloads.first(), PathSafety.resolveInside(gameDir, f.path), f.hashes["sha1"], f.fileSize, f.path.substringAfterLast('/'))
        }
        // Modrinth files come from the hosts allowed by the .mrpack specification only.
        val dm = DownloadManager(http, Mrpack.POLICY, freeSpace = { storage.freeBytes() }, logger = logger)
        val summary = dm.downloadAll(requests, ::onProgress)
        if (!summary.isSuccess) throw IOException("${summary.failures.size} modpack file(s) failed: ${summary.failures.first().reason}")
        Mrpack.extractOverrides(pack, gameDir)
    }

    private fun stageLabel(s: InstallStage) = when (s) {
        InstallStage.METADATA -> "Metadata"; InstallStage.LIBRARIES -> "Libraries"; InstallStage.CLIENT -> "Game client"
        InstallStage.ASSET_INDEX -> "Asset index"; InstallStage.ASSETS -> "Assets"; InstallStage.NATIVES -> "Native libraries"
        InstallStage.VERIFY -> "Verifying"; InstallStage.DONE -> "Done"
    }

    private fun stage(s: String) {
        stage = s
        setProgressAsync(workDataOf(KEY_STAGE to s, KEY_FRACTION to -1f))
    }

    private fun onProgress(p: DownloadProgress) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 500 && p.active.isNotEmpty()) return
        lastNotify = now
        val detail = buildString {
            append("${p.completedFiles}/${p.totalFiles} files")
            if (p.totalBytes > 0) append(" · ${FileOps.formatSize(p.downloadedBytes)} / ${FileOps.formatSize(p.totalBytes)}")
            if (p.bytesPerSecond > 0) append(" · ${FileOps.formatSize(p.bytesPerSecond)}/s")
            p.etaSeconds?.let { append(" · ${it / 60}m ${it % 60}s left") }
        }
        setProgressAsync(Data.Builder().putString(KEY_STAGE, stage).putFloat(KEY_FRACTION, p.fraction).putString(KEY_DETAIL, detail).build())
        runCatching { setForegroundAsync(foreground(p.fraction, "$stage · $detail")) }
    }

    private fun foreground(fraction: Float, text: String): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, EloaApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Installing game files")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), fraction <= 0f)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0f, "Preparing")

    companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_MRPACK = "mrpack"
        const val KEY_STAGE = "stage"
        const val KEY_FRACTION = "fraction"
        const val KEY_DETAIL = "detail"
        const val KEY_ERROR = "error"
        private const val NOTIFICATION_ID = 4201

        fun workName(profileId: String) = "install-$profileId"
        fun profileTag(profileId: String) = "profile:$profileId"

        fun enqueue(context: Context, profileId: String, mrpackPath: String? = null) {
            val req = OneTimeWorkRequestBuilder<InstallWorker>()
                .setInputData(workDataOf(KEY_PROFILE to profileId, KEY_MRPACK to mrpackPath))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("install")
                .addTag(profileTag(profileId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(profileId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, profileId: String) = WorkManager.getInstance(context).cancelUniqueWork(workName(profileId))
    }
}
