package dev.eloa.launcher.core.data.logging

import android.util.Log
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.common.logging.LogLevel
import dev.eloa.launcher.core.common.logging.SecretRedactor
import dev.eloa.launcher.core.data.db.LogDao
import dev.eloa.launcher.core.data.db.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Centralised logger: redacts secrets, mirrors to Logcat, batches inserts into Room and keeps
 * the table bounded ([maxEntries]). Never blocks the caller.
 */
class DbLogger(
    private val dao: LogDao,
    scope: CoroutineScope,
    @Volatile var minLevel: LogLevel = LogLevel.DEBUG,
    @Volatile var maxEntries: Int = 5000,
    private val mirrorToLogcat: Boolean = true,
) : AppLogger {
    private val channel = Channel<LogEntity>(capacity = 2048, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            val batch = ArrayList<LogEntity>()
            for (first in channel) {
                batch += first
                while (batch.size < 200) batch += channel.tryReceive().getOrNull() ?: break
                runCatching { dao.insertAndTrim(batch.toList(), maxEntries) }
                    .onFailure { Log.e("EloaLogger", "Failed to persist logs", it) }
                batch.clear()
            }
        }
    }

    override fun log(level: LogLevel, component: String, event: String, throwable: Throwable?, context: Map<String, String>) {
        if (level < minLevel) return
        val safeEvent = SecretRedactor.redact(event)
        val safeContext = SecretRedactor.redactContext(context)
        val stack = throwable?.let { t -> StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().let(SecretRedactor::redact).take(16_000) }
        if (mirrorToLogcat) {
            val tag = "Eloa/${component.take(18)}"
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, safeEvent)
                LogLevel.INFO -> Log.i(tag, safeEvent)
                LogLevel.WARNING -> Log.w(tag, safeEvent)
                LogLevel.ERROR, LogLevel.CRITICAL -> Log.e(tag, safeEvent)
            }
        }
        channel.trySend(
            LogEntity(
                timestamp = System.currentTimeMillis(),
                level = level.name,
                component = component,
                event = safeEvent,
                errorType = throwable?.javaClass?.name,
                stackTrace = stack,
                context = if (safeContext.isEmpty()) null else Json.encodeToString(JsonObject.serializer(), JsonObject(safeContext.mapValues { JsonPrimitive(it.value) })),
            ),
        )
    }

    companion object {
        fun format(e: LogEntity): String = buildString {
            append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(e.timestamp)))
            append(" [").append(e.level).append("] ").append(e.component).append(": ").append(e.event)
            e.errorType?.let { append(" <").append(it).append('>') }
            e.context?.let { append(" ").append(it) }
            e.stackTrace?.let { append('\n').append(it) }
        }
    }
}
