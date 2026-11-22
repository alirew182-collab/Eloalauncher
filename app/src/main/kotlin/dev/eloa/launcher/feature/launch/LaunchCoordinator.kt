package dev.eloa.launcher.feature.launch

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.BuildConfig
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.LaunchHistoryDao
import dev.eloa.launcher.core.data.db.LaunchHistoryEntity
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.launch.LaunchPlan
import dev.eloa.launcher.domain.java.launch.LaunchPlanBuilder
import dev.eloa.launcher.domain.java.launch.LaunchPreflight
import dev.eloa.launcher.domain.java.launch.LaunchSettings
import dev.eloa.launcher.domain.java.launch.PreflightIssue
import dev.eloa.launcher.domain.java.launch.PreflightSeverity
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.domain.java.render.GpuProfile
import dev.eloa.launcher.domain.java.render.RendererCatalog
import dev.eloa.launcher.domain.java.render.RendererId
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo
import dev.eloa.launcher.core.data.device.DeviceInfo
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.feature.bedrock.BedrockRepository
import dev.eloa.launcher.feature.bedrock.PackageInstallerGateway
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.java.InstallState
import dev.eloa.launcher.feature.java.JavaRepository
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.feature.java.state
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Executes a prepared Java Edition launch plan on a JVM runtime. */
interface GameRuntimeBackend {
    val available: Boolean
    val description: String
    suspend fun launch(plan: LaunchPlan, runtime: JavaRuntimeInfo)
}

/**
 * This build bundles no JVM+LWJGL+GL translation stack for Android, so Java Edition cannot be
 * executed. Reported honestly through preflight instead of pretending to launch.
 */
class UnavailableBackend @Inject constructor() : GameRuntimeBackend {
    override val available = false
    override val description = "No Java Edition execution backend is bundled with this build."
    override suspend fun launch(plan: LaunchPlan, runtime: JavaRuntimeInfo) = throw IOException(description)
}

sealed interface LaunchOutcome {
    data object Started : LaunchOutcome
    data class Blocked(val title: String, val issues: List<PreflightIssue>, val commandPreview: String?) : LaunchOutcome
    /** [resId] (when non-zero) is a localized explanation shown instead of [message]. */
    data class Failed(val message: String, @androidx.annotation.StringRes val resId: Int = 0) : LaunchOutcome
}

@Singleton
class LaunchCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bedrock: BedrockRepository,
    private val installer: PackageInstallerGateway,
    private val java: JavaRepository,
    private val profiles: ProfileRepository,
    private val accounts: AccountRepository,
    private val storage: LauncherStorage,
    private val device: DeviceInfoProvider,
    private val history: LaunchHistoryDao,
    private val backend: GameRuntimeBackend,
    private val logger: AppLogger,
    private val settings: SettingsRepository,
) {
    fun recent(limit: Int) = history.observeRecent(limit)

    suspend fun launchBedrock(id: Long): LaunchOutcome {
        val plan = bedrock.planInstall(id) ?: return LaunchOutcome.Failed("Version not found")
        val e = plan.entity
        val installed = installer.installedPackage(e.packageName)
        val intent = installer.launchIntent(e.packageName)
        val outcome = when {
            installed == null || intent == null -> LaunchOutcome.Blocked("${e.displayName} is not installed",
                listOf(PreflightIssue(PreflightSeverity.BLOCKER, "NOT_INSTALLED", "The game must be installed through the system installer first.", "Use “Install” on this version.")), null)
            installed.versionCode != e.versionCode -> LaunchOutcome.Blocked("A different version is installed",
                listOf(PreflightIssue(PreflightSeverity.BLOCKER, "VERSION_MISMATCH",
                    "Installed: ${installed.versionName ?: installed.versionCode}; selected: ${e.versionName ?: e.versionCode}. Android allows only one copy of a package.",
                    "Install the selected version (${plan.explanation}) or launch the installed one from the Bedrock screen.")), null)
            else -> {
                context.startActivity(intent)
                bedrock.markLaunched(id)
                LaunchOutcome.Started
            }
        }
        record("BEDROCK", id.toString(), e.displayName, e.versionName ?: "", outcome)
        return outcome
    }

    /** Launches whatever Minecraft Bedrock copy is installed, regardless of the library. */
    fun launchInstalledBedrock(): Boolean {
        val intent = installer.launchIntent(dev.eloa.launcher.domain.bedrock.apk.KnownPackages.MINECRAFT_BEDROCK) ?: return false
        context.startActivity(intent)
        return true
    }

    /**
     * Runs the full pre-launch pipeline for a Java profile and hands the plan to the runtime
     * backend. Nothing is simulated: if any requirement is missing the exact reasons are returned.
     */
    suspend fun prepareJava(profileId: String, modeOverride: LaunchMode? = null, quickPlayWorld: String? = null): LaunchOutcome = withContext(Dispatchers.IO) {
        val p = profiles.get(profileId) ?: return@withContext LaunchOutcome.Failed("Profile not found")
        val mode = modeOverride ?: runCatching { LaunchMode.valueOf(settings.current().launchMode) }.getOrDefault(LaunchMode.OFFICIAL_ACCOUNT)
        val outcome = try {
            val launchId = p.launchVersionId
            if (p.state != InstallState.INSTALLED && p.state != InstallState.NEEDS_PROCESSORS || launchId == null) {
                LaunchOutcome.Blocked("${p.name} is not installed", listOf(PreflightIssue(PreflightSeverity.BLOCKER, "NOT_INSTALLED",
                    "Game files for this profile have not been installed yet.", "Tap “Install” on the profile.")), null)
            } else {
                val identity = accounts.launchIdentity(mode)
                val version = java.installer().resolveLocal(launchId)
                val gameDir = storage.instance(p.gameDirName).also { it.ensure() }.gameDir
                val world = if (mode == LaunchMode.LOCAL_SINGLEPLAYER) {
                    val saves = File(gameDir, "saves").listFiles { f -> f.isDirectory && File(f, "level.dat").isFile }.orEmpty().map { it.name }
                    quickPlayWorld?.takeIf { it in saves } ?: saves.firstOrNull()
                } else null
                val dev = device.collect(includeGpu = true)
                val rendererId = rendererFor(p.renderer, dev.gpuProfile(), p.minecraftVersion)
                val env = rendererId?.let { RendererCatalog.environment(it, p.resolutionScale) }.orEmpty()
                val plan = LaunchPlanBuilder.build(version, java.dirs, gameDir, identity,
                    LaunchSettings(p.minMemoryMb, p.maxMemoryMb, p.width, p.height,
                        p.jvmArgs.split(' ').filter { it.isNotBlank() }, p.gameArgs.split(' ').filter { it.isNotBlank() },
                        launcherVersion = BuildConfig.VERSION_NAME, quickPlaySingleplayer = world, environment = env),
                    java.ruleEnv, java.clientJarIdFor(launchId))
                val runtime = java.runtimeFor(plan.requiredJavaMajor, p.runtimeName)
                val issues = LaunchPreflight.check(plan, runtime, device.memorySnapshot().first / 1_048_576, p.pendingProcessors, backend.available).toMutableList()
                if (rendererId == null) issues += PreflightIssue(PreflightSeverity.BLOCKER, "NO_RENDERER",
                    "No compatible OpenGL translation layer for this GPU and Minecraft ${p.minecraftVersion}.", "Check Performance → Renderers.")
                if (mode == LaunchMode.LOCAL_SINGLEPLAYER && world == null) issues += PreflightIssue(PreflightSeverity.BLOCKER, "NO_WORLD",
                    "Local singleplayer needs an existing world in this profile.", "Create or import a world first.")
                if (issues.any { it.severity == PreflightSeverity.BLOCKER } || runtime == null) {
                    LaunchOutcome.Blocked("Java Edition cannot start yet", issues, plan.redactedCommandLine())
                } else {
                    backend.launch(plan, runtime)
                    profiles.markPlayed(p.id)
                    LaunchOutcome.Started
                }
            }
        } catch (e: Exception) {
            logger.e("Launch", "Java launch failed for ${p.name}", e)
            LaunchOutcome.Failed(e.userMessage())
        }
        record("JAVA", p.id, p.name, "${p.minecraftVersion} · ${p.loader} · ${mode.name}", outcome)
        outcome
    }

    private fun rendererFor(stored: String, gpu: GpuProfile, mc: String): RendererId? =
        runCatching { RendererId.valueOf(stored) }.getOrNull() ?: RendererCatalog.autoSelect(gpu, mc)

    private suspend fun record(edition: String, id: String, title: String, subtitle: String, o: LaunchOutcome) {
        val msg = when (o) { is LaunchOutcome.Blocked -> o.issues.firstOrNull { it.severity == PreflightSeverity.BLOCKER }?.message ?: o.title; is LaunchOutcome.Failed -> o.message; else -> null }
        history.insert(LaunchHistoryEntity(edition = edition, targetId = id, title = title, subtitle = subtitle, launchedAt = System.currentTimeMillis(), success = o is LaunchOutcome.Started, message = msg))
        logger.i("Launch", "$edition launch: ${o::class.simpleName}", mapOf("target" to title))
    }
}

/** GPU facts needed by the renderer catalog. */
fun DeviceInfo.gpuProfile(): GpuProfile {
    val (maj, min) = glesVersion.split('.').let { (it.getOrNull(0)?.toIntOrNull() ?: 2) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    return GpuProfile(gpu.renderer, maj, min, if (vulkanSupported) vulkanVersion ?: "1.0" else null)
}
