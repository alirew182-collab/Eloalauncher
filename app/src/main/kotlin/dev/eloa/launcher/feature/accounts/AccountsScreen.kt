package dev.eloa.launcher.feature.accounts

import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.domain.java.auth.DeviceCode
import dev.eloa.launcher.domain.java.launch.AccountSnapshot
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.domain.java.launch.LaunchModes
import dev.eloa.launcher.domain.java.launch.ModeAvailability
import dev.eloa.launcher.domain.java.launch.ModeContext
import dev.eloa.launcher.feature.common.NetworkStatus
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.blockerText
import dev.eloa.launcher.feature.common.description
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.common.label
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.java.TYPE_ELYBY
import dev.eloa.launcher.feature.java.TYPE_MSA
import dev.eloa.launcher.feature.common.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SignInState {
    data object Idle : SignInState
    data object Requesting : SignInState
    data class WaitingForUser(val code: DeviceCode) : SignInState
    data object WaitingForBrowser : SignInState
    data class Failed(val message: String) : SignInState
}

sealed interface ElyState {
    data object Idle : ElyState
    data object Busy : ElyState
    data object NeedsCode : ElyState
    data object Done : ElyState
    data class Failed(val message: String) : ElyState
}

data class AccountsUi(val settings: LauncherSettings = LauncherSettings(), val accounts: List<AccountEntity> = emptyList(), val modes: List<ModeAvailability> = emptyList(), val online: Boolean = false)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accounts: AccountRepository,
    profiles: dev.eloa.launcher.feature.java.ProfileRepository,
    storage: dev.eloa.launcher.core.data.storage.LauncherStorage,
    private val settings: SettingsRepository,
    private val network: NetworkStatus,
    private val messages: UserMessages,
) : ViewModel() {
    private val online = MutableStateFlow(network.isOnline())
    val ui: StateFlow<AccountsUi> = combine(settings.settings, accounts.accounts, online, profiles.profiles) { s, accs, on, profs ->
        val active = accs.firstOrNull { it.active }
        // Quick Play availability depends on the selected profile's version and its worlds.
        val p = profs.firstOrNull { it.id == s.selectedProfileId } ?: profs.firstOrNull()
        val worlds = p?.let { java.io.File(storage.instance(it.gameDirName).gameDir, "saves").list()?.toList() }.orEmpty()
        val ctx = ModeContext(active?.let { AccountSnapshot(it.username, it.minecraftTokenExpiresAt) }, on, System.currentTimeMillis(), p?.minecraftVersion, worlds)
        AccountsUi(s, accs, LaunchModes.evaluateAll(ctx), on)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUi())

    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn
    private var job: Job? = null

    fun refreshNetwork() { online.value = network.isOnline() }
    fun setMode(m: LaunchMode) = viewModelScope.launch { settings.update { it.copy(launchMode = m.name) } }
    fun saveClientId(id: String) = viewModelScope.launch { settings.update { it.copy(msClientId = id) }; messages.info(R.string.msg_client_id_saved) }

    fun startDeviceCode() {
        job?.cancel()
        job = viewModelScope.launch {
            _signIn.value = SignInState.Requesting
            try {
                val code = accounts.startSignIn()
                _signIn.value = SignInState.WaitingForUser(code)
                val acc = accounts.completeSignIn(code)
                _signIn.value = SignInState.Idle
                messages.info(R.string.msg_signed_in_as, acc.username)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _signIn.value = SignInState.Failed(e.userMessage())
            }
        }
    }

    /** Returns the authorization URL to open in the system browser. */
    fun startBrowser(onUrl: (String) -> Unit) = viewModelScope.launch {
        try {
            val url = accounts.beginBrowserSignIn()
            _signIn.value = SignInState.WaitingForBrowser
            onUrl(url)
        } catch (e: Exception) { _signIn.value = SignInState.Failed(e.userMessage()) }
    }

    fun cancel() { job?.cancel(); _signIn.value = SignInState.Idle }

    private fun act(ok: Int, block: suspend () -> Unit) = viewModelScope.launch { runCatching { block() }.onSuccess { messages.info(ok) }.onFailure { messages.error(it.userMessage()) } }
    fun setActive(id: String) = act(R.string.msg_account_selected) { accounts.setActive(id) }
    fun refresh(id: String) = act(R.string.msg_session_refreshed) { accounts.refresh(id) }
    fun signOut(id: String) = act(R.string.msg_signed_out) { accounts.signOut(id) }

    // Ely.by sign-in state for the dialog. The password lives only in the dialog's text field.
    private val _ely = MutableStateFlow<ElyState>(ElyState.Idle)
    val ely: StateFlow<ElyState> = _ely
    fun resetEly() { _ely.value = ElyState.Idle }
    fun signInEly(login: String, password: String, totp: String?) = viewModelScope.launch {
        _ely.value = ElyState.Busy
        try {
            val acc = accounts.addElyBy(login, password, totp)
            _ely.value = ElyState.Done
            messages.info(R.string.msg_signed_in_as, acc.username)
        } catch (e: dev.eloa.launcher.domain.java.auth.ElyAuthException.TwoFactorRequired) {
            _ely.value = ElyState.NeedsCode
        } catch (e: dev.eloa.launcher.domain.java.auth.ElyAuthException.InvalidCredentials) {
            _ely.value = ElyState.Failed(str(R.string.ely_wrong_credentials))
        } catch (e: dev.eloa.launcher.feature.java.ElyRequiresOwnershipException) {
            _ely.value = ElyState.Failed(str(R.string.ely_needs_owner))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            _ely.value = ElyState.Failed(e.userMessage())
        }
    }
}

@Composable
fun AccountsScreen(padding: PaddingValues, vm: AccountsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var signOutId by remember { mutableStateOf<String?>(null) }
    var elyDialog by remember { mutableStateOf(false) }
    val ely by vm.ely.collectAsStateWithLifecycle()
    val s = ui.settings
    val hasClient = s.msClientId.isNotBlank()

    LazyColumn(Modifier.padding(padding).testTag("accounts"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionHeader(stringResource(R.string.microsoft_accounts), subtitle = stringResource(R.string.official_sign_in_only_your_password)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                if (ui.accounts.isEmpty()) EmptyState(Icons.Outlined.AccountCircle, stringResource(R.string.no_accounts), stringResource(R.string.java_edition_can_only_be_launched))
                ui.accounts.forEach { a ->
                    Row(Modifier.padding(vertical = 6.dp).testTag("account_row"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.username, fontWeight = FontWeight.SemiBold)
                            if (a.type == TYPE_ELYBY) {
                                Text(stringResource(R.string.account_type_ely), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.ely_added_1_s, formatDate(a.addedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(stringResource(R.string.account_type_ms) + " · " + stringResource(R.string.session_valid_until_1_s, formatDate(a.minecraftTokenExpiresAt)), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.ownership_verified_added_1_s, formatDate(a.addedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (a.active) StatusChip(stringResource(R.string.active), StatusLevel.OK) else TextButton(onClick = { vm.setActive(a.id) }) { Text(stringResource(R.string.use)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.refresh(a.id) }, enabled = ui.online) { Text(stringResource(R.string.refresh)) }
                        TextButton(onClick = { signOutId = a.id }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startBrowser { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } } },
                        enabled = hasClient && ui.online, modifier = Modifier.weight(1f).testTag("sign_in_browser")) { Text(stringResource(R.string.sign_in_browser)) }
                    FilledTonalButton(onClick = vm::startDeviceCode, enabled = hasClient && ui.online, modifier = Modifier.weight(1f).testTag("sign_in")) { Text(stringResource(R.string.sign_in_with_code)) }
                }
                if (!ui.online) Text(stringResource(R.string.you_are_offline_signing_in_needs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        item { SectionHeader(stringResource(R.string.ely_title), subtitle = stringResource(R.string.ely_subtitle)) }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("ely_card")) {
                val hasOwner = ui.accounts.any { it.type == TYPE_MSA }
                Text(stringResource(if (hasOwner) R.string.ely_body else R.string.ely_needs_owner), style = MaterialTheme.typography.bodySmall,
                    color = if (hasOwner) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                FilledTonalButton(onClick = { elyDialog = true }, enabled = hasOwner && ui.online, modifier = Modifier.testTag("ely_sign_in")) { Text(stringResource(R.string.ely_sign_in)) }
            }
        }
        item { SectionHeader(stringResource(R.string.launch_mode), subtitle = stringResource(R.string.each_mode_is_a_real_different)) }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("launch_modes")) {
                ui.modes.forEach { m ->
                    val selected = runCatching { LaunchMode.valueOf(s.launchMode) }.getOrNull() == m.mode
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = selected, onClick = { vm.setMode(m.mode) }, enabled = m.available || selected, modifier = Modifier.testTag("mode_radio_${m.mode.name}"))
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Text(m.mode.label(), style = MaterialTheme.typography.titleSmall)
                            Text(m.mode.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            m.blockers.forEach { b -> Text(blockerText(b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                TextButton(onClick = vm::refreshNetwork) { Text(if (ui.online) stringResource(R.string.network_online_re_check) else stringResource(R.string.network_offline_re_check)) }
            }
        }

        item { SectionHeader(stringResource(R.string.azure_application), subtitle = stringResource(R.string.minecraft_sign_in_requires_an_azure)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                var clientId by remember(s.msClientId) { mutableStateOf(s.msClientId) }
                val valid = clientId.isEmpty() || Regex("^[0-9a-fA-F-]{36}$").matches(clientId)
                OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text(stringResource(R.string.application_client_id)) }, singleLine = true, isError = !valid,
                    modifier = Modifier.fillMaxWidth().testTag("client_id"),
                    supportingText = { Text(stringResource(R.string.enable_public_client_flows_for_browser)) })
                OutlinedButton(onClick = { vm.saveClientId(clientId) }, enabled = clientId != s.msClientId && valid) { Text(stringResource(R.string.save_id)) }
            }
        }
    }

    signOutId?.let { id -> ConfirmDialog(stringResource(R.string.sign_out_2), stringResource(R.string.the_stored_tokens_for_this_account), stringResource(R.string.sign_out), { vm.signOut(id); signOutId = null }, { signOutId = null }, destructive = true) }
    when (val si = signIn) {
        is SignInState.Requesting -> AlertDialog(onDismissRequest = vm::cancel, title = { Text(stringResource(R.string.contacting_microsoft)) }, text = { CircularProgressIndicator() }, confirmButton = { TextButton(onClick = vm::cancel) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } })
        is SignInState.WaitingForBrowser -> AlertDialog(onDismissRequest = vm::cancel, title = { Text(stringResource(R.string.continue_in_the_browser)) },
            text = { Text(stringResource(R.string.finish_signing_in_on_microsoft_s)) }, confirmButton = { TextButton(onClick = vm::cancel) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } })
        is SignInState.WaitingForUser -> AlertDialog(
            modifier = Modifier.testTag("device_code_dialog"),
            onDismissRequest = {}, title = { Text(stringResource(R.string.sign_in_with_microsoft)) },
            text = {
                Column {
                    Text(stringResource(R.string.s_1_open_1_s, si.code.verificationUri))
                    Text(stringResource(R.string.s_2_enter_this_code))
                    Spacer(Modifier.height(8.dp))
                    Text(si.code.userCode, style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.waiting_for_you_to_finish_in), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", si.code.userCode))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, si.code.verificationUri.toUri())) }
                }) { Text(stringResource(R.string.copy_code_open)) }
            },
            dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } },
        )
        is SignInState.Failed -> AlertDialog(onDismissRequest = vm::cancel, title = { Text(stringResource(R.string.sign_in_failed)) }, text = { Text(si.message) }, confirmButton = { TextButton(onClick = vm::cancel) { Text("OK") } })
        SignInState.Idle -> Unit
    }
    if (elyDialog) ElyDialog(ely, vm, onClose = { elyDialog = false; vm.resetEly() })
}

@Composable
private fun ElyDialog(state: ElyState, vm: AccountsViewModel, onClose: () -> Unit) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(state) { if (state is ElyState.Done) onClose() }
    if (state is ElyState.Done) return
    AlertDialog(
        modifier = Modifier.testTag("ely_dialog"),
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ely_sign_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ely_password_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(login, { login = it.trim().take(100) }, label = { Text(stringResource(R.string.ely_login)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ely_login"),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
                OutlinedTextField(password, { password = it.take(200) }, label = { Text(stringResource(R.string.ely_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ely_password"),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                if (state is ElyState.NeedsCode || code.isNotEmpty()) {
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.ely_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ely_code"),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Text(stringResource(R.string.ely_code_hint), style = MaterialTheme.typography.bodySmall)
                }
                if (state is ElyState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (state is ElyState.Busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            Button(onClick = { vm.signInEly(login, password, code.ifBlank { null }) }, enabled = state !is ElyState.Busy && login.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.testTag("ely_confirm")) { Text(stringResource(R.string.ely_sign_in)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } },
    )
}
