package dev.eloa.launcher.feature.downloads

import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.UsageBar
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.java.InstallState
import dev.eloa.launcher.feature.java.InstallWorker
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.feature.java.state
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DownloadJob(val profile: JavaProfileEntity, val state: WorkInfo.State?, val stage: String?, val fraction: Float?, val detail: String?, val error: String?)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val storage: LauncherStorage,
    private val messages: UserMessages,
) : ViewModel() {
    val jobs: StateFlow<List<DownloadJob>> = combine(
        profiles.profiles, WorkManager.getInstance(context).getWorkInfosByTagFlow("install"),
    ) { profs, infos ->
        profs.mapNotNull { p ->
            val mine = infos.filter { it.tags.contains(InstallWorker.profileTag(p.id)) }
            val w = mine.firstOrNull { !it.state.isFinished } ?: mine.lastOrNull()
            if (w == null && p.state != InstallState.FAILED && p.state != InstallState.INSTALLING) return@mapNotNull null
            val f = w?.progress?.getFloat(InstallWorker.KEY_FRACTION, -1f) ?: -1f
            DownloadJob(p, w?.state, w?.progress?.getString(InstallWorker.KEY_STAGE), if (f < 0) null else f, w?.progress?.getString(InstallWorker.KEY_DETAIL),
                w?.outputData?.getString(InstallWorker.KEY_ERROR) ?: p.lastError)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Stops the job; partially downloaded files are kept and resumed with HTTP Range on the next start. */
    fun pause(id: String) = InstallWorker.cancel(context, id)
    fun resume(id: String) { InstallWorker.enqueue(context, id); messages.info(R.string.msg_download_resumed) }
    /** Stops the job and, if no other installation is running, deletes partial downloads. */
    fun cancel(id: String) = viewModelScope.launch {
        InstallWorker.cancel(context, id)
        val others = WorkManager.getInstance(context).getWorkInfosByTagFlow("install").first()
            .any { !it.state.isFinished && !it.tags.contains(InstallWorker.profileTag(id)) }
        val freed = if (others) 0L else withContext(Dispatchers.IO) { storage.cleanupTemp(0) }
        messages.info(R.string.msg_download_cancelled, FileOps.formatSize(freed))
    }
    fun freeBytes() = storage.freeBytes()
    fun totalBytes() = storage.totalBytes()
}

@Composable
fun DownloadsScreen(padding: PaddingValues, vm: DownloadsViewModel = hiltViewModel()) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(padding).testTag("downloads"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                val free = vm.freeBytes(); val total = vm.totalBytes()
                Text(stringResource(R.string.free_storage_1_s_of_2, FileOps.formatSize(free), FileOps.formatSize(total)))
                Spacer(Modifier.height(6.dp))
                UsageBar(1f - free.toFloat() / total.coerceAtLeast(1))
                Text(stringResource(R.string.files_come_only_from_official_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SectionHeader(stringResource(R.string.installation_queue)) }
        if (jobs.isEmpty()) item { EmptyState(Icons.Outlined.CloudDownload, stringResource(R.string.no_downloads), stringResource(R.string.installing_a_profile_version_or_modpack)) }
        items(jobs, key = { it.profile.id }) { j ->
            LauncherCard(Modifier.fillMaxWidth().testTag("download_job")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.profile.name, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.minecraft_1_s_2_s, j.profile.minecraftVersion, j.profile.loader.lowercase()), style = MaterialTheme.typography.bodySmall)
                    }
                    when (j.state) {
                        WorkInfo.State.RUNNING -> StatusChip(stringResource(R.string.downloading), StatusLevel.INFO)
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> StatusChip(stringResource(R.string.queued), StatusLevel.INFO)
                        WorkInfo.State.SUCCEEDED -> StatusChip(stringResource(R.string.done), StatusLevel.OK)
                        WorkInfo.State.FAILED -> StatusChip(stringResource(R.string.failed), StatusLevel.ERROR)
                        WorkInfo.State.CANCELLED -> StatusChip(stringResource(R.string.paused), StatusLevel.WARNING)
                        null -> if (j.profile.state == InstallState.FAILED) StatusChip(stringResource(R.string.failed), StatusLevel.ERROR) else StatusChip(stringResource(R.string.interrupted), StatusLevel.WARNING)
                    }
                }
                val active = j.state == WorkInfo.State.RUNNING || j.state == WorkInfo.State.ENQUEUED
                if (active) {
                    Spacer(Modifier.height(8.dp))
                    if (j.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                    j.stage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    j.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (!active && j.error != null) Text(j.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active) {
                        OutlinedButton(onClick = { vm.pause(j.profile.id) }) { Text(stringResource(R.string.pause)) }
                        OutlinedButton(onClick = { vm.cancel(j.profile.id) }) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) }
                    } else if (j.state != WorkInfo.State.SUCCEEDED) {
                        OutlinedButton(onClick = { vm.resume(j.profile.id) }, modifier = Modifier.testTag("download_resume")) { Text(if (j.state == WorkInfo.State.CANCELLED) stringResource(R.string.resume) else stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_retry)) }
                    }
                }
            }
        }
    }
}
