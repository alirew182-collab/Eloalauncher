package dev.eloa.launcher

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.feature.bedrock.BedrockRepository
import dev.eloa.launcher.feature.java.ProfileConfig
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.nav.Destination
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Tag that proves each destination rendered its own content. */
val SCREEN_TAGS = mapOf(
    Destination.HOME to "home", Destination.BEDROCK to "import_apk", Destination.JAVA to "java_tab_0",
    Destination.VERSIONS to "versions", Destination.MODS to "mods", Destination.MODPACKS to "modpacks",
    Destination.TEXTURES to "content_RESOURCE_PACKS", Destination.SHADERS to "content_SHADER_PACKS",
    Destination.ADDONS to "addons", Destination.FILES to "files", Destination.PROFILES to "profiles",
    Destination.SETTINGS to "settings", Destination.DIAGNOSTICS to "diagnostics", Destination.LOGS to "logs",
    Destination.LIBRARY to "library", Destination.WORLDS to "worlds", Destination.ACCOUNTS to "accounts",
    Destination.RUNTIMES to "runtimes", Destination.DOWNLOADS to "downloads", Destination.PERFORMANCE to "performance",
    Destination.GAME_SETTINGS to "game_settings", Destination.CONTROLS to "controls", Destination.NOTIFICATIONS to "notifications",
    Destination.UPDATES to "updates", Destination.PRIVACY to "privacy",
)

abstract class UiTestBase : HiltTestBase() {
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    fun SemanticsNodeInteractionsProvider.waitTag(tag: String, timeout: Long = 10_000) = try {
        compose.waitUntil(timeout) { tick(); onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) { throw AssertionError("Node with tag '$tag' did not appear", e) }

    fun waitText(text: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { tick(); compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Advances Robolectric's main-looper clock so delayed coroutines (timeouts, debounces) can run. */
    private fun tick() = org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)

    /**
     * Robolectric's paused main looper does not advance time by itself; flush delayed main-thread
     * tasks (e.g. WhileSubscribed(5000) stop timers of ViewModels left behind) so Compose can go idle.
     */
    fun settle() { org.robolectric.shadows.ShadowLooper.idleMainLooper(6, java.util.concurrent.TimeUnit.SECONDS); compose.waitForIdle() }

    open fun go(d: Destination) {
        compose.onNodeWithTag("menu_button").performClick()
        compose.waitTag("nav_${d.route}")
        compose.onNodeWithTag("nav_${d.route}").performScrollTo().performClick()
        compose.waitTag(SCREEN_TAGS.getValue(d))
        settle()
    }
}

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PhoneUiTest : UiTestBase() {
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var bedrock: BedrockRepository

    @Test fun `every section is reachable from the drawer`() {
        compose.waitTag("home")
        Destination.entries.forEach { go(it) }
    }

    @Test fun `rapid switching between screens does not crash`() {
        repeat(2) { listOf(Destination.MODS, Destination.FILES, Destination.HOME, Destination.ADDONS, Destination.LOGS, Destination.PROFILES, Destination.BEDROCK).forEach { go(it) } }
    }

    @Test fun `empty states are shown`() {
        go(Destination.BEDROCK); waitText("No Bedrock versions yet")
        go(Destination.ADDONS); waitText("No add-ons yet")
        go(Destination.MODS); waitText("No profile")
        go(Destination.PROFILES); waitText("No profiles yet")
        go(Destination.DIAGNOSTICS); waitText("Not run yet")
    }

    @Test fun `play without profile explains what to do`() {
        compose.waitTag("launch_button")
        compose.onNodeWithTag("launch_button").performClick()
        compose.waitTag("launch_result")
        compose.onNodeWithText("Create a Java Edition profile first", substring = true).assertExists()
        compose.onNodeWithText("OK").performClick()
    }

    @Test fun `profile editor validates input`() {
        go(Destination.PROFILES)
        compose.onNodeWithTag("new_profile").performClick()
        compose.waitTag("profile_editor")
        compose.onNodeWithTag("profile_save").assertIsNotEnabled()
        compose.onNodeWithTag("profile_name").performTextInput("PvP")
        // Version list cannot be downloaded in the test sandbox → still invalid, with a message
        compose.onNodeWithTag("profile_save").assertIsNotEnabled()
        waitText("Choose a Minecraft version")
    }

    @Test fun `profile card actions and delete confirmation`() {
        runBlocking { profiles.create(ProfileConfig(name = "Creative World", minecraftVersion = "1.20.1", loader = "FORGE")) }
        go(Destination.PROFILES)
        compose.waitTag("profile_card")
        compose.onNodeWithText("Creative World").assertExists()
        compose.onNodeWithTag("profile_menu").performClick()
        compose.waitTag("profile_delete")
        compose.onNodeWithTag("profile_delete").performClick()
        compose.waitTag("confirm_dialog")
        compose.onNodeWithTag("confirm_button").performClick()
        waitText("No profiles yet")
    }

    @Test fun `java screen shows profile status and tabs`() {
        runBlocking { profiles.create(ProfileConfig(name = "Vanilla", minecraftVersion = "1.21.4", loader = "VANILLA")) }
        go(Destination.JAVA)
        waitText("Not installed")
        compose.onNodeWithTag("java_tab_1").performClick(); compose.waitTag("content_WORLDS")
        compose.onNodeWithTag("java_tab_3").performClick(); compose.waitTag("content_CONFIGS")
    }

    @Test fun `bedrock library card opens details and removal dialog`() {
        runBlocking { bedrock.import(listOf(fileUri("b.apk", fixtureApk("base.apk")), fileUri("s.apk", fixtureApk("split-arm64.apk")))) { _, _ -> } }
        go(Destination.BEDROCK)
        compose.waitTag("bedrock_version")
        compose.onNodeWithTag("bedrock_version").performClick()
        compose.waitTag("version_sheet")
        compose.onNodeWithText("com.mojang.minecraftpe").assertExists()
        compose.onNodeWithTag("version_sheet").performScrollToNode(hasTestTag("bedrock_delete"))
        compose.onNodeWithTag("bedrock_delete").performClick()
        compose.waitTag("confirm_dialog")
        compose.onNodeWithTag("confirm_button").performClick()
        waitText("No Bedrock versions yet")
    }

    @Test fun `logs clear dialog and level filter`() {
        go(Destination.LOGS)
        compose.onNodeWithTag("level_DEBUG").performClick()
        compose.onNodeWithTag("logs_clear").performClick()
        compose.waitTag("confirm_dialog")
        compose.onNodeWithTag("confirm_button").performClick()
        waitText("No log entries")
    }

    @Test fun `files screen lists created folders and offers actions`() {
        java.io.File(context.filesDir, "exports/worlds-backup").mkdirs()
        go(Destination.FILES)
        compose.onNodeWithText("Exports").performClick()
        compose.waitTag("file_row")
        compose.onNodeWithText("worlds-backup").assertExists()
        compose.onNodeWithTag("files_new_folder").assertExists()
    }

    @Test fun `dark and light themes render`() {
        listOf("DARK", "LIGHT", "SYSTEM").forEach { mode ->
            runBlocking { settings.update { it.copy(themeMode = mode, accent = "AMETHYST") } }
            compose.waitForIdle()
            go(Destination.SETTINGS)
            go(Destination.HOME)
        }
    }

    @Test fun `diagnostics runs and lists findings`() {
        go(Destination.DIAGNOSTICS)
        compose.onNodeWithTag("run_diagnostics").performClick()
        compose.waitTag("diag_issue", timeout = 30_000)
    }
}

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [30], qualifiers = "w1280dp-h800dp-mdpi")
class TabletUiTest : UiTestBase() {
    override fun go(d: Destination) {
        compose.onNodeWithTag("nav_${d.route}").performScrollTo().performClick()
        compose.waitTag(SCREEN_TAGS.getValue(d))
        settle()
    }

    @Test fun `permanent drawer navigation on large screens`() {
        compose.waitTag("home")
        compose.onNodeWithTag("menu_button").assertDoesNotExist()
        Destination.entries.forEach { go(it) }
    }
}

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [26], qualifiers = "w360dp-h640dp-hdpi")
class SmallOldPhoneUiTest : UiTestBase() {
    @Test fun `android 8 small screen renders all sections`() {
        compose.waitTag("home")
        Destination.entries.forEach { go(it) }
    }
}
