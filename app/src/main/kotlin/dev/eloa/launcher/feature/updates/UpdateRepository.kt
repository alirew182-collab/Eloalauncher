package dev.eloa.launcher.feature.updates

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.BuildConfig
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.bedrock.apk.ApkInspection
import dev.eloa.launcher.domain.bedrock.apk.ApkInspector
import dev.eloa.launcher.domain.java.update.UpdateChecker
import dev.eloa.launcher.domain.java.update.UpdateInfo
import dev.eloa.launcher.domain.java.update.UpdateIntegrityException
import dev.eloa.launcher.domain.java.update.UpdateResult
import dev.eloa.launcher.feature.bedrock.PackageInstallerGateway
import dev.eloa.launcher.feature.notifications.NoticeType
import dev.eloa.launcher.feature.notifications.NotificationCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launcher self-update from the project's GitHub releases. An update is installed only when
 * (1) the release publishes a SHA-256 that matches the download and (2) the APK is signed with the
 * same certificate as the installed launcher. Installation always goes through the system
 * installer, which asks the user to confirm.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val storage: LauncherStorage,
    private val installer: PackageInstallerGateway,
    private val notifications: NotificationCenter,
    private val logger: AppLogger,
) {
    private fun checker() = UpdateChecker(http, BuildConfig.UPDATE_REPO)
    private val apkFile get() = File(storage.root, "updates/update.apk")

    suspend fun check(): UpdateResult {
        val s = settings.current()
        val r = checker().check(BuildConfig.VERSION_NAME, s.includePrereleases)
        settings.update { it.copy(lastUpdateCheckAt = System.currentTimeMillis()) }
        logger.i("Updates", "Update check: ${r::class.simpleName}")
        return r
    }

    /** Downloads, verifies checksum and signer, then starts the system installer. */
    suspend fun downloadAndInstall(info: UpdateInfo, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val f = apkFile
        checker().download(info, f) { done, total -> if (total > 0) onProgress(done.toFloat() / total) }
        try {
            val inspection = ApkInspector().inspect(f)
            val apk = (inspection as? ApkInspection.Ok)?.info ?: throw UpdateIntegrityException("The downloaded file is not a valid APK.")
            if (apk.packageName != context.packageName) throw UpdateIntegrityException("The update is for a different app (${apk.packageName}).")
            if (!apk.signature.verified) throw UpdateIntegrityException("The update's signature is invalid.")
            val installedCerts = installer.installedPackage(context.packageName)?.certificateSha256.orEmpty()
            if (installedCerts.isEmpty() || apk.signature.certificateSha256.none { it in installedCerts }) {
                throw UpdateIntegrityException("The update is signed with a different key than the installed launcher; it was not installed.")
            }
            installer.install(listOf(f), context.packageName) { onProgress(it) }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
    }

    suspend fun scheduleOrCancel() {
        val wm = WorkManager.getInstance(context)
        if (settings.current().autoUpdateCheck) {
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(UpdateCheckWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } else wm.cancelUniqueWork(UpdateCheckWorker.NAME)
    }

    suspend fun notifyIfAvailable() {
        val r = check()
        if (r is UpdateResult.Available) {
            notifications.post(NoticeType.UPDATE_AVAILABLE, R.string.notice_update_title, context.getString(R.string.notice_update_body, r.info.version), "updates")
        }
    }
}

/** Daily update check (only when enabled by the user; unmetered network, battery not low). */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repo: UpdateRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!settings.current().autoUpdateCheck) return Result.success()
        return runCatching { repo.notifyIfAvailable() }.fold({ Result.success() }, { Result.retry() })
    }

    companion object { const val NAME = "update-check" }
}
