package dev.eloa.launcher.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.BuildConfig
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.settings.HomeLayout
import dev.eloa.launcher.core.data.settings.UiDensity
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.UsageBar
import dev.eloa.launcher.core.designsystem.theme.AccentColor
import dev.eloa.launcher.core.designsystem.theme.ThemeMode
import dev.eloa.launcher.feature.common.Locales
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.common.label
import dev.eloa.launcher.nav.Destination
import kotlin.math.roundToInt

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, modifier = Modifier.testTag("switch_$tag"))
    }
}

@Composable
private fun LinkRow(d: Destination, onNavigate: (Destination) -> Unit) {
    LauncherCard(Modifier.fillMaxWidth().testTag("link_${d.route}"), onClick = { onNavigate(d) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(d.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(d.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

@Composable
fun SettingsScreen(padding: PaddingValues, onNavigate: (Destination) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val st by vm.storageStats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val systemLabel = stringResource(R.string.language_system)
    val themeLabels = mapOf(ThemeMode.SYSTEM to stringResource(R.string.theme_system), ThemeMode.LIGHT to stringResource(R.string.theme_light), ThemeMode.DARK to stringResource(R.string.theme_dark))
    val densityLabels = UiDensity.entries.associateWith { it.label() }
    val homeLabels = HomeLayout.entries.associateWith { it.label() }
    val currentLang = Locales.current(context)
    val accentLabels = AccentColor.entries.associateWith { stringResource(it.labelRes) }

    LazyColumn(Modifier.padding(padding).testTag("settings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader(stringResource(R.string.appearance)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
                ChipRow(ThemeMode.entries, runCatching { ThemeMode.valueOf(s.themeMode) }.getOrDefault(ThemeMode.SYSTEM), { themeLabels.getValue(it) }, { m -> vm.update { it.copy(themeMode = m.name) } })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.accent_colour), style = MaterialTheme.typography.titleSmall)
                ChipRow(AccentColor.entries, runCatching { AccentColor.valueOf(s.accent) }.getOrDefault(AccentColor.GRASS), { accentLabels.getValue(it) }, { a -> vm.update { it.copy(accent = a.name) } })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                ChipRow(Locales.SUPPORTED, currentLang.takeIf { it in Locales.SUPPORTED } ?: "", { Locales.displayName(it, systemLabel) }, { tag ->
                    vm.update { it.copy(language = tag) }
                    context.activity()?.let { Locales.apply(it, tag) }
                })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.home_screen), style = MaterialTheme.typography.titleSmall)
                ChipRow(HomeLayout.entries, s.homeLayout, { homeLabels.getValue(it) }, { h -> vm.update { it.copy(homeLayout = h) } })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.interface_density), style = MaterialTheme.typography.titleSmall)
                ChipRow(UiDensity.entries, s.uiDensity, { densityLabels.getValue(it) }, { d -> vm.update { it.copy(uiDensity = d) } })
                Text(stringResource(R.string.element_size_1_s, (s.uiScale * 100).roundToInt()), style = MaterialTheme.typography.titleSmall)
                Slider(s.uiScale, { v -> vm.update { it.copy(uiScale = (v * 20).roundToInt() / 20f) } }, valueRange = 0.85f..1.3f, steps = 8, modifier = Modifier.testTag("ui_scale"))
                ToggleRow(stringResource(R.string.animations), stringResource(R.string.screen_transitions_and_loading_shimmer), !s.reduceMotion, { v -> vm.update { it.copy(reduceMotion = !v) } }, "animations")
                ToggleRow(stringResource(R.string.blur_effects), stringResource(R.string.android_12_and_newer_only), s.enableBlur, { v -> vm.update { it.copy(enableBlur = v) } }, "blur")
            }
        }

        item { SectionHeader(stringResource(R.string.game)) }
        listOf(Destination.PERFORMANCE, Destination.GAME_SETTINGS, Destination.CONTROLS, Destination.RUNTIMES, Destination.ACCOUNTS).forEach { d -> item { LinkRow(d, onNavigate) } }

        item { SectionHeader(stringResource(R.string.nav_notifications)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                ToggleRow(stringResource(R.string.system_notifications), stringResource(R.string.finished_downloads_errors_updates_and_overheatin), s.systemNotifications, { v -> vm.update { it.copy(systemNotifications = v) } }, "notifications")
                ToggleRow(stringResource(R.string.overheating_warnings), null, s.thermalWarnings, { v -> vm.update { it.copy(thermalWarnings = v) } }, "thermal")
            }
        }

        item { SectionHeader(stringResource(R.string.storage_and_cache)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                st?.let { x ->
                    LabeledValue(stringResource(R.string.launcher_data), FileOps.formatSize(x.launcherBytes))
                    LabeledValue(stringResource(R.string.cache), stringResource(R.string.s_1_s_limit_2_s_mb, FileOps.formatSize(x.cacheBytes), s.cacheLimitMb))
                    LabeledValue(stringResource(R.string.free_on_device), "${FileOps.formatSize(x.freeBytes)} of ${FileOps.formatSize(x.totalBytes)}")
                    UsageBar(1f - x.freeBytes.toFloat() / x.totalBytes.coerceAtLeast(1))
                }
                ToggleRow(stringResource(R.string.clean_temporary_files_automatically), stringResource(R.string.last_run_1_s, formatDate(s.lastCleanupAt)), s.autoCleanupTemp, { v -> vm.update { it.copy(autoCleanupTemp = v) } }, "cleanup")
                Text(stringResource(R.string.remove_temporary_files_older_than_1, s.tempMaxAgeHours))
                Slider(s.tempMaxAgeHours.toFloat(), { v -> vm.update { it.copy(tempMaxAgeHours = v.roundToInt()) } }, valueRange = 1f..168f)
                Text(stringResource(R.string.parallel_downloads_1_s, s.maxConcurrentDownloads))
                Slider(s.maxConcurrentDownloads.toFloat(), { v -> vm.update { it.copy(maxConcurrentDownloads = v.roundToInt()) } }, valueRange = 1f..16f, steps = 14)
                FilledTonalButton(onClick = vm::cleanNow, modifier = Modifier.testTag("clean_now")) { Text(stringResource(R.string.clean_now)) }
            }
        }

        item { SectionHeader(stringResource(R.string.logging)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.minimum_level), style = MaterialTheme.typography.titleSmall)
                ChipRow(listOf("DEBUG", "INFO", "WARNING", "ERROR"), s.minLogLevel, { it.lowercase().replaceFirstChar { c -> c.uppercase() } }, { l -> vm.update { it.copy(minLogLevel = l) } })
                Text(stringResource(R.string.keep_at_most_1_s_entries, s.maxLogEntries))
                Slider(s.maxLogEntries.toFloat(), { v -> vm.update { it.copy(maxLogEntries = (v / 500).roundToInt() * 500) } }, valueRange = 500f..50_000f)
            }
        }

        item { SectionHeader(stringResource(R.string.about)) }
        listOf(Destination.UPDATES, Destination.PRIVACY, Destination.LOGS, Destination.DIAGNOSTICS).forEach { d -> item { LinkRow(d, onNavigate) } }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                LabeledValue(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text(stringResource(R.string.eloa_launcher_is_not_affiliated_with), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.open_source_components_androidx_jetpack_compose), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
