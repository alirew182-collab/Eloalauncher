package dev.eloa.launcher.feature.common

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import java.util.concurrent.TimeUnit

/** Periodic, low-priority cleanup of temporary files (only when the device is idle-ish and not low on battery). */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val storage: LauncherStorage,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = settings.current()
        if (!s.autoCleanupTemp) return Result.success()
        val freed = storage.cleanupTemp(s.tempMaxAgeHours * 3_600_000L)
        settings.update { it.copy(lastCleanupAt = System.currentTimeMillis()) }
        logger.i("Cleanup", "Temporary files cleaned", mapOf("freed" to FileOps.formatSize(freed)))
        return Result.success()
    }

    companion object {
        private const val NAME = "temp-cleanup"
        fun schedule(context: Context, s: LauncherSettings) {
            val wm = WorkManager.getInstance(context)
            if (!s.autoCleanupTemp) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<CleanupWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresDeviceIdle(s.limitBackgroundWork).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
