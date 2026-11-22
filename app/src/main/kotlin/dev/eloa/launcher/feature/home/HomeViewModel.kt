package dev.eloa.launcher.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.db.BedrockVersionEntity
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.db.LaunchHistoryEntity
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.settings.Edition
import dev.eloa.launcher.core.data.settings.HomeLayout
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.bedrock.apk.KnownPackages
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.domain.java.launch.LaunchModes
import dev.eloa.launcher.domain.java.launch.ModeAvailability
import dev.eloa.launcher.domain.java.launch.ModeContext
import dev.eloa.launcher.feature.bedrock.BedrockRepository
import dev.eloa.launcher.feature.bedrock.PackageInstallerGateway
import dev.eloa.launcher.feature.common.NetworkStatus
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.feature.launch.LaunchCoordinator
import dev.eloa.launcher.feature.launch.LaunchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SystemStatus(val availRamMb: Long, val totalRamMb: Long, val freeStorage: Long, val totalStorage: Long, val launcherBytes: Long, val online: Boolean)

data class BedrockStatus(val installedVersion: String?, val launchable: Boolean)

data class HomeState(
    val loading: Boolean = true,
    val layout: HomeLayout = HomeLayout.CARDS,
    val edition: Edition = Edition.JAVA,
    val bedrockVersions: List<BedrockVersionEntity> = emptyList(),
    val bedrock: BedrockStatus = BedrockStatus(null, false),
    val profiles: List<JavaProfileEntity> = emptyList(),
    val selectedProfile: JavaProfileEntity? = null,
    val account: AccountEntity? = null,
    val recent: List<LaunchHistoryEntity> = emptyList(),
    val system: SystemStatus? = null,
    val mode: LaunchMode = LaunchMode.OFFICIAL_ACCOUNT,
    val modes: List<ModeAvailability> = emptyList(),
    val launchCount: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    bedrock: BedrockRepository,
    profiles: ProfileRepository,
    accounts: AccountRepository,
    private val launcher: LaunchCoordinator,
    private val installer: PackageInstallerGateway,
    private val device: DeviceInfoProvider,
    private val storage: LauncherStorage,
    private val network: NetworkStatus,
    private val messages: UserMessages,
) : ViewModel() {
    private val system = flow {
        while (true) {
            val (avail, total) = device.memorySnapshot()
            val st = storage.stats()
            emit(SystemStatus(avail / 1_048_576, total / 1_048_576, st.freeBytes, st.totalBytes, st.launcherBytes, network.isOnline()))
            delay(10_000)
        }
    }.flowOn(Dispatchers.IO)

    private val bedrockStatus = flow {
        while (true) {
            emit(BedrockStatus(installer.installedPackage(KnownPackages.MINECRAFT_BEDROCK)?.versionName, installer.launchIntent(KnownPackages.MINECRAFT_BEDROCK) != null))
            delay(15_000)
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<HomeState> = combine(
        settings.settings, bedrock.versions, profiles.profiles, accounts.accounts,
        combine(launcher.recent(8), system, bedrockStatus) { r, s, b -> Triple(r, s, b) },
    ) { s, versions, profs, accs, (recent, sys, bstat) ->
        val selected = profs.firstOrNull { it.id == s.selectedProfileId } ?: profs.firstOrNull()
        val active = accs.firstOrNull { it.active }
        val worlds = selected?.let { p -> File(storage.instance(p.gameDirName).gameDir, "saves").list()?.toList() }.orEmpty()
        val ctx = ModeContext(active?.let { dev.eloa.launcher.domain.java.launch.AccountSnapshot(it.username, it.minecraftTokenExpiresAt) },
            sys.online, System.currentTimeMillis(), selected?.minecraftVersion, worlds)
        HomeState(
            loading = false, layout = s.homeLayout, edition = s.selectedEdition, bedrockVersions = versions, bedrock = bstat,
            profiles = profs, selectedProfile = selected, account = active, recent = recent, system = sys,
            mode = runCatching { LaunchMode.valueOf(s.launchMode) }.getOrDefault(LaunchMode.OFFICIAL_ACCOUNT),
            modes = LaunchModes.evaluateAll(ctx), launchCount = recent.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val _launching = MutableStateFlow<Edition?>(null)
    val launching: StateFlow<Edition?> = _launching
    private val _outcome = MutableStateFlow<LaunchOutcome?>(null)
    val outcome: StateFlow<LaunchOutcome?> = _outcome

    fun selectProfile(id: String) = viewModelScope.launch { settings.update { it.copy(selectedProfileId = id, selectedEdition = Edition.JAVA) } }
    fun selectMode(m: LaunchMode) = viewModelScope.launch { settings.update { it.copy(launchMode = m.name) } }

    fun launchJava(profileId: String? = null) {
        if (_launching.value != null) return
        val s = state.value
        val target = profileId ?: s.selectedProfile?.id
        _launching.value = Edition.JAVA
        viewModelScope.launch {
            try {
                settings.update { it.copy(selectedEdition = Edition.JAVA) }
                _outcome.value = target?.let { launcher.prepareJava(it) } ?: LaunchOutcome.Failed("", R.string.err_no_profile)
            } finally { _launching.value = null }
        }
    }

    /** Starts the installed Minecraft Bedrock through its own launcher activity. */
    fun launchBedrock() {
        if (_launching.value != null) return
        _launching.value = Edition.BEDROCK
        viewModelScope.launch {
            try {
                settings.update { it.copy(selectedEdition = Edition.BEDROCK) }
                val ok = withContext(Dispatchers.Main) { launcher.launchInstalledBedrock() }
                if (!ok) _outcome.value = LaunchOutcome.Failed("", R.string.err_bedrock_not_installed)
                else messages.info(R.string.msg_bedrock_started)
            } finally { _launching.value = null }
        }
    }

    fun dismissOutcome() { _outcome.value = null }
}
