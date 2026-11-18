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
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo
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
    data class Failed(val message: String) : LaunchOutcome
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

    suspend fun prepareJava(profileId: String): LaunchOutcome = withContext(Dispatchers.IO) {
        val p = profiles.get(profileId) ?: return@withContext LaunchOutcome.Failed("Profile not found")
        val outcome = try {
            val launchId = p.launchVersionId
            if (p.state != InstallState.INSTALLED && p.state != InstallState.NEEDS_PROCESSORS || launchId == null) {
                LaunchOutcome.Blocked("${p.name} is not installed", listOf(PreflightIssue(PreflightSeverity.BLOCKER, "NOT_INSTALLED",
                    "Game files for this profile have not been installed yet.", "Tap “Install” on the profile.")), null)
            } else {
                val identity = accounts.launchIdentity()
                val version = java.installer().resolveLocal(launchId)
                val gameDir = storage.instance(p.gameDirName).also { it.ensure() }.gameDir
                val plan = LaunchPlanBuilder.build(version, java.dirs, gameDir, identity,
                    LaunchSettings(p.minMemoryMb, p.maxMemoryMb, p.width, p.height,
                        p.jvmArgs.split(' ').filter { it.isNotBlank() }, p.gameArgs.split(' ').filter { it.isNotBlank() },
                        launcherVersion = BuildConfig.VERSION_NAME),
                    java.ruleEnv, java.clientJarIdFor(launchId))
                val runtime = java.runtimeFor(plan.requiredJavaMajor)
                val issues = LaunchPreflight.check(plan, runtime, device.memorySnapshot().first / 1_048_576, p.pendingProcessors, backend.available)
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
        record("JAVA", p.id, p.name, "${p.minecraftVersion} · ${p.loader}", outcome)
        outcome
    }

    private suspend fun record(edition: String, id: String, title: String, subtitle: String, o: LaunchOutcome) {
        val msg = when (o) { is LaunchOutcome.Blocked -> o.issues.firstOrNull { it.severity == PreflightSeverity.BLOCKER }?.message ?: o.title; is LaunchOutcome.Failed -> o.message; else -> null }
        history.insert(LaunchHistoryEntity(edition = edition, targetId = id, title = title, subtitle = subtitle, launchedAt = System.currentTimeMillis(), success = o is LaunchOutcome.Started, message = msg))
        logger.i("Launch", "$edition launch: ${o::class.simpleName}", mapOf("target" to title))
    }
}
