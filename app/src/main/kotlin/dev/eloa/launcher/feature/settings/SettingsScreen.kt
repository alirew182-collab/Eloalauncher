package dev.eloa.launcher.feature.settings

import androidx.core.net.toUri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.BuildConfig
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.settings.PerformancePreset
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.UsageBar
import dev.eloa.launcher.core.designsystem.theme.AccentColor
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.core.designsystem.theme.ThemeMode
import dev.eloa.launcher.feature.common.formatDate
import kotlin.math.roundToInt

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, tag: String = title) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, modifier = Modifier.testTag("switch_$tag"))
    }
}

@Composable
fun SettingsScreen(padding: PaddingValues, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val dev by vm.deviceInfo.collectAsStateWithLifecycle()
    val st by vm.storageStats.collectAsStateWithLifecycle()
    val accounts by vm.accountList.collectAsStateWithLifecycle()
    val runtimes by vm.runtimes.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val runtimePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importRuntime) }
    var signOutId by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.padding(padding).testTag("settings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionHeader("Appearance") }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                ChipRow(ThemeMode.entries, runCatching { ThemeMode.valueOf(s.themeMode) }.getOrDefault(ThemeMode.SYSTEM), { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { m -> vm.update { it.copy(themeMode = m.name) } })
                Spacer(Modifier.height(8.dp))
                Text("Accent colour", style = MaterialTheme.typography.titleSmall)
                ChipRow(AccentColor.entries, runCatching { AccentColor.valueOf(s.accent) }.getOrDefault(AccentColor.GRASS), { it.label }, { a -> vm.update { it.copy(accent = a.name) } })
                ToggleRow("Blur effects", "Used only on Android 12+ where it is GPU-accelerated", s.enableBlur, { v -> vm.update { it.copy(enableBlur = v) } })
                ToggleRow("Reduce motion", "Disables shimmer and decorative animations", s.reduceMotion, { v -> vm.update { it.copy(reduceMotion = v) } })
            }
        }

        item { SectionHeader("Performance", subtitle = "Presets change launcher settings and new-profile defaults only. No system settings are modified and no FPS gain is promised.") }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("device_card")) {
                Text("This device", style = MaterialTheme.typography.titleSmall)
                val d = dev
                if (d == null) SkeletonList(2) else {
                    LabeledValue("Model", "${d.manufacturer} ${d.model}")
                    LabeledValue("SoC", listOfNotNull(d.socManufacturer, d.socModel).joinToString(" ").ifEmpty { d.cpuName ?: d.hardware })
                    LabeledValue("CPU", "${d.cpuCores} cores" + (d.cpuMaxFreqMhz.maxOrNull()?.let { " · up to $it MHz" } ?: ""))
                    LabeledValue("GPU", d.gpu.renderer ?: "unknown")
                    LabeledValue("OpenGL ES", "${d.glesVersion}${d.gpu.glVersion?.let { " ($it)" } ?: ""}")
                    LabeledValue("Vulkan", if (d.vulkanSupported) d.vulkanVersion ?: "supported" else "not supported")
                    LabeledValue("ABI", d.supportedAbis.joinToString())
                    LabeledValue("RAM", "${d.availRamMb} MB free of ${d.totalRamMb} MB" + if (d.isLowRamDevice) " (low-RAM device)" else "")
                    LabeledValue("Android", "${d.androidRelease} (API ${d.sdkInt})")
                    LabeledValue("Thermal", listOfNotNull(d.thermalStatus, d.thermalHeadroom?.let { "headroom %.2f".format(it) }, d.batteryTempC?.let { "battery %.1f °C".format(it) }).joinToString(" · ").ifEmpty { "not available" })
                    TextButton(onClick = { vm.refreshDevice() }) { Text("Re-measure") }
                }
            }
        }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                val rec = vm.recommended()
                Text("Preset: ${s.performancePreset.name.lowercase().replace('_', ' ')}" + (rec?.let { " · recommended: ${it.name.lowercase().replace('_', ' ')}" } ?: ""), style = MaterialTheme.typography.titleSmall)
                ChipRow(PerformancePreset.entries, s.performancePreset, { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }, vm::applyPreset)
                vm.specFor(s.performancePreset)?.takeIf { s.performancePreset != PerformancePreset.CUSTOM }?.let { spec ->
                    Text("Java memory ${spec.javaMaxMemoryMb} MB · render distance suggestion ${spec.renderDistance} · ${spec.maxConcurrentDownloads} parallel downloads", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("Default Java memory for new profiles: ${s.defaultMaxMemoryMb} MB")
                val maxRam = ((dev?.totalRamMb ?: 4096) - 512).coerceAtLeast(1024).toFloat()
                Slider(s.defaultMaxMemoryMb.toFloat().coerceIn(512f, maxRam), { v -> vm.update { it.copy(defaultMaxMemoryMb = (v / 128).roundToInt() * 128, performancePreset = PerformancePreset.CUSTOM) } }, valueRange = 512f..maxRam)
                Text("Parallel downloads: ${s.maxConcurrentDownloads}")
                Slider(s.maxConcurrentDownloads.toFloat(), { v -> vm.update { it.copy(maxConcurrentDownloads = v.roundToInt(), performancePreset = PerformancePreset.CUSTOM) } }, valueRange = 1f..16f, steps = 14)
                Text("Interface refresh rate cap: ${if (s.uiRefreshCapHz == 0) "display default" else "${s.uiRefreshCapHz} Hz"}")
                ChipRow(listOf(0, 30, 60, 90, 120), s.uiRefreshCapHz, { if (it == 0) "Default" else "$it Hz" }, { hz -> vm.update { it.copy(uiRefreshCapHz = hz, performancePreset = PerformancePreset.CUSTOM) } })
                ToggleRow("Limit background work", "Periodic maintenance runs only while the device is idle", s.limitBackgroundWork, { v -> vm.update { it.copy(limitBackgroundWork = v) } })
            }
        }

        item { SectionHeader("Storage & cache") }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                st?.let { x ->
                    LabeledValue("Launcher data", FileOps.formatSize(x.launcherBytes))
                    LabeledValue("Cache", "${FileOps.formatSize(x.cacheBytes)} (limit ${s.cacheLimitMb} MB)")
                    LabeledValue("Free on device", "${FileOps.formatSize(x.freeBytes)} of ${FileOps.formatSize(x.totalBytes)}")
                    UsageBar(1f - x.freeBytes.toFloat() / x.totalBytes.coerceAtLeast(1))
                }
                ToggleRow("Clean temporary files automatically", "Last run: ${formatDate(s.lastCleanupAt)}", s.autoCleanupTemp, { v -> vm.update { it.copy(autoCleanupTemp = v) } })
                Text("Remove temp files older than ${s.tempMaxAgeHours} h")
                Slider(s.tempMaxAgeHours.toFloat(), { v -> vm.update { it.copy(tempMaxAgeHours = v.roundToInt()) } }, valueRange = 1f..168f)
                FilledTonalButton(onClick = vm::cleanNow, modifier = Modifier.testTag("clean_now")) { Text("Clean now") }
            }
        }

        item { SectionHeader("Microsoft accounts", subtitle = "Official sign-in (device code). Tokens are encrypted with a hardware-backed key and never logged.") }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                var clientId by remember(s.msClientId) { mutableStateOf(s.msClientId) }
                OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("Azure application (client) ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("client_id"),
                    supportingText = { Text("Register an app in Microsoft Entra ID with public client flows enabled and Minecraft API permission (approval by Mojang is required for new apps).") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.update { it.copy(msClientId = clientId) } }, enabled = clientId != s.msClientId && (clientId.isEmpty() || Regex("^[0-9a-fA-F-]{36}$").matches(clientId))) { Text("Save ID") }
                    Button(onClick = vm::startSignIn, enabled = s.msClientId.isNotBlank(), modifier = Modifier.testTag("sign_in")) { Text("Add account") }
                }
                Spacer(Modifier.height(8.dp))
                if (accounts.isEmpty()) Text("No accounts. Java Edition can only be launched with an account that owns the game.", style = MaterialTheme.typography.bodySmall)
                accounts.forEach { a ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.username, fontWeight = FontWeight.SemiBold)
                            Text("Session until ${formatDate(a.minecraftTokenExpiresAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (a.active) StatusChip("Active", StatusLevel.OK) else TextButton(onClick = { vm.setActive(a.id) }) { Text("Use") }
                        TextButton(onClick = { vm.refreshAccount(a.id) }) { Text("Refresh") }
                        TextButton(onClick = { signOutId = a.id }) { Text("Sign out") }
                    }
                }
            }
        }

        item { SectionHeader("Java runtimes", subtitle = "A Java runtime built for Android/Linux (${dev?.primaryAbi ?: "device ABI"}) packaged as .zip. It is validated (release file, libjvm.so ELF architecture) but never executed during import.") }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                if (runtimes.isEmpty()) Text("No runtimes installed.", style = MaterialTheme.typography.bodySmall)
                runtimes.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Java ${r.majorVersion}", fontWeight = FontWeight.SemiBold); Text("${r.fullVersion} · ${r.arch} · ${r.vendor ?: ""}", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { vm.deleteRuntime(r) }) { Text("Delete") }
                    }
                }
                OutlinedButton(onClick = { runtimePicker.launch(arrayOf("application/zip", "*/*")) }) { Text("Import runtime (.zip)") }
            }
        }

        item { SectionHeader("Logging") }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text("Minimum level", style = MaterialTheme.typography.titleSmall)
                ChipRow(listOf("DEBUG", "INFO", "WARNING", "ERROR"), s.minLogLevel, { it }, { l -> vm.update { it.copy(minLogLevel = l) } })
                Text("Keep at most ${s.maxLogEntries} entries")
                Slider(s.maxLogEntries.toFloat(), { v -> vm.update { it.copy(maxLogEntries = (v / 500).roundToInt() * 500) } }, valueRange = 500f..50_000f)
            }
        }

        item { SectionHeader("About") }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                LabeledValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("Eloa Launcher is not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB. You need to own the game.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text("Open-source components: AndroidX, Jetpack Compose, Material 3, Room, DataStore, WorkManager, Hilt/Dagger, kotlinx.coroutines, kotlinx.serialization, OkHttp, apksig — Apache License 2.0.", style = MonoStyle)
            }
        }
    }

    signOutId?.let { id -> ConfirmDialog("Sign out?", "The stored tokens for this account will be deleted. Game files and profiles are not affected.", "Sign out", { vm.signOut(id); signOutId = null }, { signOutId = null }, destructive = true) }
    when (val si = signIn) {
        is SignInState.Requesting -> AlertDialog(onDismissRequest = vm::cancelSignIn, title = { Text("Contacting Microsoft…") }, text = { CircularProgressIndicator() }, confirmButton = { TextButton(onClick = vm::cancelSignIn) { Text("Cancel") } })
        is SignInState.WaitingForUser -> AlertDialog(
            modifier = Modifier.testTag("device_code_dialog"),
            onDismissRequest = {}, title = { Text("Sign in with Microsoft") },
            text = {
                Column {
                    Text("1. Open ${si.code.verificationUri}\n2. Enter this code:")
                    Spacer(Modifier.height(8.dp))
                    Text(si.code.userCode, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text("Waiting for you to finish in the browser…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", si.code.userCode))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, si.code.verificationUri.toUri())) }
                }) { Text("Copy code & open") }
            },
            dismissButton = { TextButton(onClick = vm::cancelSignIn) { Text("Cancel") } },
        )
        is SignInState.Failed -> AlertDialog(onDismissRequest = vm::cancelSignIn, title = { Text("Sign-in failed") }, text = { Text(si.message) }, confirmButton = { TextButton(onClick = vm::cancelSignIn) { Text("OK") } })
        SignInState.Idle -> Unit
    }
}
