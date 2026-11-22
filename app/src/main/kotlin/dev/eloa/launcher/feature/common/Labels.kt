package dev.eloa.launcher.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.settings.HomeLayout
import dev.eloa.launcher.core.data.settings.PerformancePreset
import dev.eloa.launcher.core.data.settings.UiDensity
import dev.eloa.launcher.domain.bedrock.world.WorldProblem
import dev.eloa.launcher.domain.java.crash.CrashCause
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.domain.java.launch.ModeBlocker
import dev.eloa.launcher.domain.java.options.CloudMode
import dev.eloa.launcher.domain.java.options.GraphicsQuality
import dev.eloa.launcher.domain.java.options.ParticleLevel
import dev.eloa.launcher.domain.java.render.RendererNote
import dev.eloa.launcher.feature.performance.ThermalLevel

/** Localized names for domain enums (the domain layer has no Android resources). */

@Composable fun LaunchMode.label(): String = stringResource(when (this) {
    LaunchMode.OFFICIAL_ACCOUNT -> R.string.mode_official
    LaunchMode.ONLINE -> R.string.mode_online
    LaunchMode.OFFLINE -> R.string.mode_offline
    LaunchMode.LOCAL_SINGLEPLAYER -> R.string.mode_local
})

@Composable fun LaunchMode.description(): String = stringResource(when (this) {
    LaunchMode.OFFICIAL_ACCOUNT -> R.string.mode_official_desc
    LaunchMode.ONLINE -> R.string.mode_online_desc
    LaunchMode.OFFLINE -> R.string.mode_offline_desc
    LaunchMode.LOCAL_SINGLEPLAYER -> R.string.mode_local_desc
})

@Composable fun blockerText(b: ModeBlocker): String = stringResource(when (b) {
    ModeBlocker.NO_ACCOUNT -> R.string.blocker_no_account
    ModeBlocker.NO_NETWORK -> R.string.blocker_no_network
    ModeBlocker.TOKEN_EXPIRED_OFFLINE -> R.string.blocker_token_expired
    ModeBlocker.QUICK_PLAY_UNSUPPORTED -> R.string.blocker_quick_play
    ModeBlocker.NO_LOCAL_WORLDS -> R.string.blocker_no_worlds
})

@Composable fun PerformancePreset.label(): String = stringResource(when (this) {
    PerformancePreset.BATTERY_SAVER -> R.string.preset_battery
    PerformancePreset.BALANCED -> R.string.preset_balanced
    PerformancePreset.PERFORMANCE -> R.string.preset_performance
    PerformancePreset.EXTREME_PERFORMANCE -> R.string.preset_extreme
    PerformancePreset.CUSTOM -> R.string.preset_custom
})

@Composable fun PerformancePreset.description(): String = stringResource(when (this) {
    PerformancePreset.BATTERY_SAVER -> R.string.preset_battery_desc
    PerformancePreset.BALANCED -> R.string.preset_balanced_desc
    PerformancePreset.PERFORMANCE -> R.string.preset_performance_desc
    PerformancePreset.EXTREME_PERFORMANCE -> R.string.preset_extreme_desc
    PerformancePreset.CUSTOM -> R.string.preset_custom_desc
})

@Composable fun GraphicsQuality.label(): String = stringResource(when (this) {
    GraphicsQuality.FAST -> R.string.gfx_fast; GraphicsQuality.FANCY -> R.string.gfx_fancy; GraphicsQuality.FABULOUS -> R.string.gfx_fabulous
})

@Composable fun ParticleLevel.label(): String = stringResource(when (this) {
    ParticleLevel.ALL -> R.string.particles_all; ParticleLevel.DECREASED -> R.string.particles_decreased; ParticleLevel.MINIMAL -> R.string.particles_minimal
})

@Composable fun CloudMode.label(): String = stringResource(when (this) {
    CloudMode.OFF -> R.string.clouds_off; CloudMode.FAST -> R.string.clouds_fast; CloudMode.FANCY -> R.string.clouds_fancy
})

@Composable fun RendererNote.text(): String = stringResource(when (this) {
    RendererNote.NEEDS_GLES2 -> R.string.rn_needs_gles2
    RendererNote.NEEDS_GLES3 -> R.string.rn_needs_gles3
    RendererNote.NEEDS_VULKAN_1_1 -> R.string.rn_needs_vulkan
    RendererNote.MC_TOO_NEW_FOR_GL21 -> R.string.rn_mc_too_new
    RendererNote.LEGACY_VERSION_ONLY_TESTED_MODERN -> R.string.rn_legacy_partial
    RendererNote.MALI_VULKAN_DRIVER_ISSUES -> R.string.rn_mali_vulkan
    RendererNote.POWERVR_LIMITED -> R.string.rn_powervr
    RendererNote.ADRENO_TURNIP_BEST -> R.string.rn_adreno_turnip
    RendererNote.NO_SHADERS -> R.string.rn_no_shaders
    RendererNote.SODIUM_UNSUPPORTED -> R.string.rn_no_sodium
    RendererNote.SLOWER_THAN_NATIVE_GLES -> R.string.rn_slower
    RendererNote.HELPS_BUGGY_GLES_DRIVERS -> R.string.rn_buggy_gles
    RendererNote.NOT_INSTALLED -> R.string.rn_not_installed
})

@Composable fun CrashCause.title(): String = stringResource(when (this) {
    CrashCause.OUT_OF_MEMORY -> R.string.cc_oom
    CrashCause.NATIVE_LIBRARY_MISSING -> R.string.cc_native
    CrashCause.WRONG_JAVA_VERSION -> R.string.cc_java
    CrashCause.OPENGL_UNSUPPORTED -> R.string.cc_opengl
    CrashCause.RENDERER_CRASH -> R.string.cc_renderer
    CrashCause.MOD_MISSING_DEPENDENCY -> R.string.cc_mod_dep
    CrashCause.MOD_INCOMPATIBLE_MINECRAFT -> R.string.cc_mod_mc
    CrashCause.MOD_DUPLICATE -> R.string.cc_mod_dup
    CrashCause.MIXIN_FAILURE -> R.string.cc_mixin
    CrashCause.CORRUPTED_FILE -> R.string.cc_corrupt
    CrashCause.DISK_FULL -> R.string.cc_disk
    CrashCause.NETWORK -> R.string.cc_network
    CrashCause.INVALID_SESSION -> R.string.cc_session
    CrashCause.SIGNAL_CRASH -> R.string.cc_signal
    CrashCause.UNKNOWN -> R.string.cc_unknown
})

@Composable fun CrashCause.fix(): String = stringResource(when (this) {
    CrashCause.OUT_OF_MEMORY -> R.string.cf_oom
    CrashCause.NATIVE_LIBRARY_MISSING -> R.string.cf_native
    CrashCause.WRONG_JAVA_VERSION -> R.string.cf_java
    CrashCause.OPENGL_UNSUPPORTED -> R.string.cf_opengl
    CrashCause.RENDERER_CRASH -> R.string.cf_renderer
    CrashCause.MOD_MISSING_DEPENDENCY -> R.string.cf_mod_dep
    CrashCause.MOD_INCOMPATIBLE_MINECRAFT -> R.string.cf_mod_mc
    CrashCause.MOD_DUPLICATE -> R.string.cf_mod_dup
    CrashCause.MIXIN_FAILURE -> R.string.cf_mixin
    CrashCause.CORRUPTED_FILE -> R.string.cf_corrupt
    CrashCause.DISK_FULL -> R.string.cf_disk
    CrashCause.NETWORK -> R.string.cf_network
    CrashCause.INVALID_SESSION -> R.string.cf_session
    CrashCause.SIGNAL_CRASH -> R.string.cf_signal
    CrashCause.UNKNOWN -> R.string.cf_unknown
})

@Composable fun WorldProblem.text(): String = stringResource(when (this) {
    WorldProblem.NO_LEVEL_DAT -> R.string.wp_no_level
    WorldProblem.LEVEL_DAT_UNREADABLE -> R.string.wp_level_unreadable
    WorldProblem.NO_DB -> R.string.wp_no_db
    WorldProblem.CORRUPT_ARCHIVE -> R.string.wp_corrupt
    WorldProblem.CHECKSUM_MISMATCH -> R.string.wp_checksum
    WorldProblem.UNSAFE_PATH -> R.string.wp_unsafe
})

@Composable fun ThermalLevel.label(): String = stringResource(when (this) {
    ThermalLevel.NONE -> R.string.th_none
    ThermalLevel.LIGHT -> R.string.th_light
    ThermalLevel.MODERATE -> R.string.th_moderate
    ThermalLevel.SEVERE -> R.string.th_severe
    ThermalLevel.CRITICAL -> R.string.th_critical
    ThermalLevel.EMERGENCY -> R.string.th_emergency
    ThermalLevel.SHUTDOWN -> R.string.th_shutdown
    ThermalLevel.UNSUPPORTED -> R.string.th_unsupported
})

@Composable fun UiDensity.label(): String = stringResource(when (this) {
    UiDensity.COMPACT -> R.string.density_compact; UiDensity.DEFAULT -> R.string.density_default; UiDensity.COMFORTABLE -> R.string.density_comfortable
})

@Composable fun HomeLayout.label(): String = stringResource(when (this) {
    HomeLayout.CARDS -> R.string.home_cards; HomeLayout.DASHBOARD -> R.string.home_dashboard; HomeLayout.MINIMAL -> R.string.home_minimal
})
