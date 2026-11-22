package dev.eloa.launcher

import androidx.core.content.edit
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
import dev.eloa.launcher.feature.notifications.NotificationCenter
import dev.eloa.launcher.feature.performance.ThermalMonitor
import dev.eloa.launcher.feature.updates.UpdateRepository
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
    @Inject lateinit var thermal: ThermalMonitor
    @Inject lateinit var updates: UpdateRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(dev.eloa.launcher.feature.common.Locales.wrap(base))

    override fun onCreate() {
        super.onCreate()
        dev.eloa.launcher.feature.common.Strings.init(this)
        installCrashLogger()
        createChannels()
        appScope.launch {
            settings.settings.collect { s ->
                dbLogger.minLevel = runCatching { LogLevel.valueOf(s.minLogLevel) }.getOrDefault(LogLevel.DEBUG)
                dbLogger.maxEntries = s.maxLogEntries
            }
        }
        appScope.launch { CleanupWorker.schedule(this@EloaApp, settings.current()) }
        appScope.launch { runCatching { updates.scheduleOrCancel() } }
        thermal.start()
        logger.i("App", "Launcher started", mapOf("version" to BuildConfig.VERSION_NAME, "sdk" to android.os.Build.VERSION.SDK_INT.toString()))
    }

    /** Records uncaught exceptions as CRITICAL before the default handler terminates the process. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                logger.critical("Crash", "Uncaught exception on ${t.name}", e)
                // Synchronous write: the process is about to die, apply() could be lost.
                getSharedPreferences("crash", MODE_PRIVATE).edit(commit = true) {
                    putLong("last_crash", System.currentTimeMillis()).putString("last_crash_type", e.javaClass.name)
                }
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
        NotificationCenter.createChannel(this)
    }

    companion object { const val CHANNEL_DOWNLOADS = "downloads" }
}
