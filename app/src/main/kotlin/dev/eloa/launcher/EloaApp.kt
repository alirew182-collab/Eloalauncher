package dev.eloa.launcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.common.logging.LogLevel
import dev.eloa.launcher.core.data.logging.DbLogger
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.di.AppScope
import dev.eloa.launcher.feature.common.CleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class EloaApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logger: AppLogger
    @Inject lateinit var dbLogger: DbLogger
    @Inject lateinit var settings: SettingsRepository
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        createChannels()
        appScope.launch {
            settings.settings.collect { s ->
                dbLogger.minLevel = runCatching { LogLevel.valueOf(s.minLogLevel) }.getOrDefault(LogLevel.DEBUG)
                dbLogger.maxEntries = s.maxLogEntries
            }
        }
        appScope.launch { CleanupWorker.schedule(this@EloaApp, settings.current()) }
        logger.i("App", "Launcher started", mapOf("version" to BuildConfig.VERSION_NAME, "sdk" to android.os.Build.VERSION.SDK_INT.toString()))
    }

    /** Records uncaught exceptions as CRITICAL before the default handler terminates the process. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                logger.critical("Crash", "Uncaught exception on ${t.name}", e)
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putLong("last_crash", System.currentTimeMillis()).putString("last_crash_type", e.javaClass.name).commit()
                Thread.sleep(300) // allow the log batch to flush
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_downloads_desc)
        })
    }

    companion object { const val CHANNEL_DOWNLOADS = "downloads" }
}
