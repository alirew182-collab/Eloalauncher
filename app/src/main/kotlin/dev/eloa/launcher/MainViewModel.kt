package dev.eloa.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.LogDao
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.notifications.NotificationCenter
import dev.eloa.launcher.nav.ChromeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
    logs: LogDao,
    notifications: NotificationCenter,
    private val accounts: AccountRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val settings: StateFlow<LauncherSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    val chrome: StateFlow<ChromeState> = combine(
        notifications.unread, logs.observeErrorCount(System.currentTimeMillis() - 86_400_000), accounts.accounts,
    ) { unread, errors, accs -> ChromeState(unread, errors, accs.firstOrNull { it.active }?.username) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChromeState())

    private val _route = MutableStateFlow<String?>(null)
    val requestedRoute: StateFlow<String?> = _route
    fun requestRoute(r: String?) { _route.value = r }

    /** Redirect from the system browser after Microsoft sign-in (eloa-launcher://auth?...). */
    fun onAuthRedirect(uri: String) {
        if (!accounts.hasPendingBrowserSignIn()) { messages.error(R.string.msg_signin_no_pending); return }
        viewModelScope.launch {
            runCatching { accounts.completeBrowserSignIn(uri) }
                .onSuccess { messages.info(R.string.msg_signed_in_as, it.username); _route.value = "accounts" }
                .onFailure { messages.error(it.userMessage()) }
        }
    }
}
