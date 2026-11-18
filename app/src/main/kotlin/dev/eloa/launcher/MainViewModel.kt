package dev.eloa.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.LogDao
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository, logs: LogDao) : ViewModel() {
    val settings: StateFlow<LauncherSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    val recentErrors: StateFlow<Int> = logs.observeErrorCount(System.currentTimeMillis() - 86_400_000).stateIn(viewModelScope, SharingStarted.Eagerly, 0)
}
