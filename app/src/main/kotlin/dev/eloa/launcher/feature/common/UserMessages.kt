package dev.eloa.launcher.feature.common

import android.content.Context
import androidx.annotation.StringRes
import dev.eloa.launcher.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A snackbar message. UI text is a string resource resolved with the Activity context at display
 * time (so it follows the selected app language); [text] is used for dynamic detail such as
 * exception messages coming from lower layers.
 */
data class UserMessage(
    val text: String? = null,
    @StringRes val resId: Int = 0,
    val args: List<Any> = emptyList(),
    val isError: Boolean = false,
    @StringRes val actionRes: Int = 0,
    val action: (() -> Unit)? = null,
) {
    fun resolve(context: Context): String = if (resId != 0) context.getString(resId, *args.toTypedArray()) else text.orEmpty()
}

/** App-wide notifications shown as snackbars by the scaffold. */
@Singleton
class UserMessages @Inject constructor() {
    private val _flow = MutableSharedFlow<UserMessage>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<UserMessage> = _flow
    fun info(@StringRes resId: Int, vararg args: Any) { _flow.tryEmit(UserMessage(resId = resId, args = args.toList())) }
    fun error(@StringRes resId: Int, vararg args: Any) { _flow.tryEmit(UserMessage(resId = resId, args = args.toList(), isError = true)) }
    /** Dynamic text only (e.g. [userMessage] of an exception or a message produced by a repository). */
    fun info(text: String) { _flow.tryEmit(UserMessage(text = text)) }
    fun error(text: String) { _flow.tryEmit(UserMessage(text = text, isError = true)) }
    fun show(m: UserMessage) { _flow.tryEmit(m) }
}

/** Converts exceptions into short, user-facing explanations. */
fun Throwable.userMessage(): String = when (this) {
    is java.net.UnknownHostException -> str(R.string.err_no_internet)
    is java.net.SocketTimeoutException -> str(R.string.err_timeout)
    is javax.net.ssl.SSLException -> str(R.string.err_tls_1_s, message ?: "TLS")
    is dev.eloa.launcher.domain.java.download.InsufficientSpaceException -> message ?: str(R.string.inst_storage)
    is dev.eloa.launcher.core.common.io.PathTraversalException -> str(R.string.err_unsafe_path)
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

/** Localized variant for the common network/storage cases; falls back to [userMessage]. */
fun Throwable.userMessage(context: Context): String = when (this) {
    is java.net.UnknownHostException -> context.getString(R.string.err_no_internet)
    is java.net.SocketTimeoutException -> context.getString(R.string.err_timeout)
    is dev.eloa.launcher.core.common.io.PathTraversalException -> context.getString(R.string.err_unsafe_path)
    else -> userMessage()
}
