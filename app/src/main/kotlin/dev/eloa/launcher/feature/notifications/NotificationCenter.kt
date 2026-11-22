package dev.eloa.launcher.feature.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.MainActivity
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.NotificationDao
import dev.eloa.launcher.core.data.db.NotificationEntity
import dev.eloa.launcher.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

enum class NoticeType(val systemPriority: Int, val throttleMs: Long) {
    DOWNLOAD_DONE(NotificationCompat.PRIORITY_DEFAULT, 0),
    INSTALL_FAILED(NotificationCompat.PRIORITY_HIGH, 0),
    LOW_STORAGE(NotificationCompat.PRIORITY_HIGH, 6 * 3_600_000L),
    INCOMPATIBLE_MOD(NotificationCompat.PRIORITY_DEFAULT, 10 * 60_000L),
    UPDATE_AVAILABLE(NotificationCompat.PRIORITY_DEFAULT, 20 * 3_600_000L),
    DAMAGED_FILE(NotificationCompat.PRIORITY_HIGH, 0),
    OVERHEATING(NotificationCompat.PRIORITY_HIGH, 30 * 60_000L),
    INFO(NotificationCompat.PRIORITY_LOW, 0),
}

/**
 * Single entry point for launcher notifications: every event is stored in the in-app notification
 * centre and, if enabled and permitted, also shown as a system notification.
 */
@Singleton
class NotificationCenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: NotificationDao,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
) {
    val items: Flow<List<NotificationEntity>> = dao.observe()
    val unread: Flow<Int> = dao.observeUnread()

    suspend fun post(type: NoticeType, @StringRes title: Int, message: String, route: String? = null) =
        post(type, context.getString(title), message, route)

    suspend fun post(type: NoticeType, title: String, message: String, route: String? = null) {
        val now = System.currentTimeMillis()
        if (type.throttleMs > 0 && dao.countSince(type.name, now - type.throttleMs) > 0) return
        val id = dao.insert(NotificationEntity(type = type.name, title = title.take(120), message = message.take(1000), createdAt = now, route = route))
        dao.trim(300)
        if (settings.current().systemNotifications) showSystem(id, type, title, message, route)
    }

    private fun showSystem(id: Long, type: NoticeType, title: String, message: String, route: String?) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route ?: "notifications")
        }
        val pi = PendingIntent.getActivity(context, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(type.systemPriority)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_BASE + (id % 1000).toInt(), n)
        } catch (e: SecurityException) {
            logger.w("Notifications", "System notification not permitted")
        }
    }

    suspend fun markAllRead() = dao.markAllRead()
    suspend fun markRead(id: Long) = dao.markRead(id)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clear() = dao.clear()

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFY_BASE = 5000

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_alerts_desc)
                },
            )
        }
    }
}
