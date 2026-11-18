package dev.eloa.launcher.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.db.AddonEntity
import dev.eloa.launcher.core.data.db.BedrockVersionEntity
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.db.LaunchHistoryEntity
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.settings.Edition
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.feature.addons.AddonRepository
import dev.eloa.launcher.feature.bedrock.BedrockRepository
import dev.eloa.launcher.feature.bedrock.PackageInstallerGateway
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
import javax.inject.Inject

data class SystemStatus(val availRamMb: Long, val totalRamMb: Long, val freeStorage: Long, val totalStorage: Long, val launcherBytes: Long)

data class HomeState(
    val loading: Boolean = true,
    val edition: Edition = Edition.JAVA,
    val bedrockVersions: List<BedrockVersionEntity> = emptyList(),
    val selectedBedrock: BedrockVersionEntity? = null,
    val installedBedrockVersion: String? = null,
    val profiles: List<JavaProfileEntity> = emptyList(),
    val selectedProfile: JavaProfileEntity? = null,
    val account: AccountEntity? = null,
    val recent: List<LaunchHistoryEntity> = emptyList(),
    val recentAddons: List<AddonEntity> = emptyList(),
    val system: SystemStatus? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    bedrock: BedrockRepository,
    profiles: ProfileRepository,
    accounts: AccountRepository,
    addons: AddonRepository,
    private val launcher: LaunchCoordinator,
    private val installer: PackageInstallerGateway,
    private val device: DeviceInfoProvider,
    private val storage: LauncherStorage,
) : ViewModel() {
    private val system = flow {
        while (true) {
            val (avail, total) = device.memorySnapshot()
            val st = storage.stats()
            emit(SystemStatus(avail / 1_048_576, total / 1_048_576, st.freeBytes, st.totalBytes, st.launcherBytes))
            delay(10_000)
        }
    }.flowOn(Dispatchers.IO)

    private val installedBedrock = flow {
        emit(installer.installedPackage(dev.eloa.launcher.domain.bedrock.apk.KnownPackages.MINECRAFT_BEDROCK)?.versionName)
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<HomeState> = combine(
        settings.settings, bedrock.versions, profiles.profiles, accounts.accounts,
        combine(launcher.recent(6), addons.recent(5), system, installedBedrock) { r, a, s, i -> listOf(r, a, s, i) },
    ) { s, versions, profs, accs, extra ->
        @Suppress("UNCHECKED_CAST")
        HomeState(
            loading = false,
            edition = s.selectedEdition,
            bedrockVersions = versions,
            selectedBedrock = versions.firstOrNull { it.id == s.selectedBedrockId } ?: versions.firstOrNull(),
            installedBedrockVersion = extra[3] as String?,
            profiles = profs,
            selectedProfile = profs.firstOrNull { it.id == s.selectedProfileId } ?: profs.firstOrNull(),
            account = accs.firstOrNull { it.active },
            recent = extra[0] as List<LaunchHistoryEntity>,
            recentAddons = extra[1] as List<AddonEntity>,
            system = extra[2] as SystemStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val _launching = MutableStateFlow(false)
    val launching: StateFlow<Boolean> = _launching
    private val _outcome = MutableStateFlow<LaunchOutcome?>(null)
    val outcome: StateFlow<LaunchOutcome?> = _outcome

    fun selectEdition(e: Edition) = viewModelScope.launch { settings.update { it.copy(selectedEdition = e) } }
    fun selectBedrock(id: Long) = viewModelScope.launch { settings.update { it.copy(selectedBedrockId = id, selectedEdition = Edition.BEDROCK) } }
    fun selectProfile(id: String) = viewModelScope.launch { settings.update { it.copy(selectedProfileId = id, selectedEdition = Edition.JAVA) } }

    fun launch() {
        if (_launching.value) return
        val s = state.value
        _launching.value = true
        viewModelScope.launch {
            try {
                _outcome.value = when (s.edition) {
                    Edition.BEDROCK -> s.selectedBedrock?.let { launcher.launchBedrock(it.id) }
                        ?: if (launcher.launchInstalledBedrock()) LaunchOutcome.Started else LaunchOutcome.Failed("Import a Minecraft Bedrock APK first (Bedrock Edition → Import).")
                    Edition.JAVA -> s.selectedProfile?.let { launcher.prepareJava(it.id) } ?: LaunchOutcome.Failed("Create a Java Edition profile first (Profiles → New).")
                }
            } finally { _launching.value = false }
        }
    }

    fun dismissOutcome() { _outcome.value = null }
}
