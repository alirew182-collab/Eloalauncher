package dev.eloa.launcher.core.common.logging

enum class LogLevel { DEBUG, INFO, WARNING, ERROR, CRITICAL }

/** Central logging contract. Implementations must call [SecretRedactor] before persisting. */
interface AppLogger {
    fun log(
        level: LogLevel,
        component: String,
        event: String,
        throwable: Throwable? = null,
        context: Map<String, String> = emptyMap(),
    )

    fun d(component: String, event: String, context: Map<String, String> = emptyMap()) = log(LogLevel.DEBUG, component, event, null, context)
    fun i(component: String, event: String, context: Map<String, String> = emptyMap()) = log(LogLevel.INFO, component, event, null, context)
    fun w(component: String, event: String, t: Throwable? = null, context: Map<String, String> = emptyMap()) = log(LogLevel.WARNING, component, event, t, context)
    fun e(component: String, event: String, t: Throwable? = null, context: Map<String, String> = emptyMap()) = log(LogLevel.ERROR, component, event, t, context)
    fun critical(component: String, event: String, t: Throwable? = null, context: Map<String, String> = emptyMap()) = log(LogLevel.CRITICAL, component, event, t, context)
}

/** Logger used in pure-JVM code and tests. */
object NoOpLogger : AppLogger {
    override fun log(level: LogLevel, component: String, event: String, throwable: Throwable?, context: Map<String, String>) = Unit
}

/** Removes secrets (tokens, passwords, auth headers) from strings before they are logged. */
object SecretRedactor {
    private const val MASK = "***"
    private val SENSITIVE_KEYS = Regex(
        "(?i)(access_?token|refresh_?token|id_?token|password|passwd|secret|authorization|client_secret|xsts|user_?hash|session|bearer|api_?key|device_code)",
    )
    private val PATTERNS = listOf(
        // "access_token": "value"  and access_token=value
        Regex("(?i)(\"?(?:access_?token|refresh_?token|id_?token|password|client_secret|device_code|token|accessToken|refreshToken)\"?\\s*[:=]\\s*\"?)([^\"&,\\s}]+)"),
        Regex("(?i)(Bearer\\s+)([A-Za-z0-9._\\-~+/=]+)"),
        Regex("(?i)(XBL3\\.0\\s+x=)([^;\\s]+;[^\\s\"]+)"),
        // JWTs
        Regex("()(eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,})"),
    )

    fun redact(text: String): String {
        var result = text
        for (p in PATTERNS) result = p.replace(result) { m -> m.groupValues[1] + MASK }
        return result
    }

    fun redactContext(context: Map<String, String>): Map<String, String> =
        context.mapValues { (k, v) -> if (SENSITIVE_KEYS.containsMatchIn(k)) MASK else redact(v) }
}
