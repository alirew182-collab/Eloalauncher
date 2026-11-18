package dev.eloa.launcher.feature.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class UserMessage(val text: String, val isError: Boolean = false, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** App-wide notifications shown as snackbars by the scaffold. */
@Singleton
class UserMessages @Inject constructor() {
    private val _flow = MutableSharedFlow<UserMessage>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<UserMessage> = _flow
    fun info(text: String) { _flow.tryEmit(UserMessage(text)) }
    fun error(text: String) { _flow.tryEmit(UserMessage(text, isError = true)) }
    fun show(m: UserMessage) { _flow.tryEmit(m) }
}

/** Converts exceptions into short, user-facing explanations. */
fun Throwable.userMessage(): String = when (this) {
    is java.net.UnknownHostException -> "No internet connection or the server is unreachable."
    is java.net.SocketTimeoutException -> "The server did not respond in time. Check your connection and retry."
    is javax.net.ssl.SSLException -> "Secure connection failed (${message ?: "TLS error"})."
    is dev.eloa.launcher.domain.java.download.InsufficientSpaceException -> message ?: "Not enough storage."
    is dev.eloa.launcher.core.common.io.PathTraversalException -> "Rejected unsafe file path."
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
