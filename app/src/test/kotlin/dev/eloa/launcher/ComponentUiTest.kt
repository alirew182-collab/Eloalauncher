package dev.eloa.launcher

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.ErrorBanner
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.TextInputDialog
import dev.eloa.launcher.core.designsystem.theme.AccentColor
import dev.eloa.launcher.core.designsystem.theme.LauncherTheme
import dev.eloa.launcher.core.designsystem.theme.ThemeMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Design-system components in the app theme (dark/light, every accent). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComponentUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun `text input dialog validates and confirms`() {
        var result: String? = null
        rule.runOnUiThread { rule.activity.setTheme(R.style.Theme_Eloa); rule.activity.enableEdgeToEdge() }
        rule.setContent {
            LauncherTheme(ThemeMode.DARK) {
                var open by remember { mutableStateOf(true) }
                if (open) TextInputDialog("New folder", "Name", "", { result = it; open = false }, { open = false },
                    validate = { if (!PathSafety.isValidFileName(it)) "Invalid name" else null }, confirmLabel = "Create")
            }
        }
        rule.onNodeWithTag("input_confirm").assertIsNotEnabled()
        rule.onNodeWithText("Invalid name").assertExists()
        rule.onNodeWithTag("input_field").performTextInput("a/b")
        rule.onNodeWithTag("input_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("input_field").performTextInput("c") // still contains '/'
        rule.onNodeWithTag("input_confirm").assertIsNotEnabled()
    }

    @Test fun `text input dialog returns trimmed value`() {
        var result: String? = null
        rule.setContent { LauncherTheme(ThemeMode.LIGHT) { TextInputDialog("Rename", "Name", "  world ", { result = it }, {}) } }
        rule.onNodeWithTag("input_confirm").assertIsEnabled().performClick()
        assertEquals("world", result)
    }

    @Test fun `confirm dialog callbacks`() {
        var confirmed = false; var dismissed = false
        rule.setContent { LauncherTheme { ConfirmDialog("Delete?", "Permanently", "Delete", { confirmed = true }, { dismissed = true }, destructive = true) } }
        rule.onNodeWithText("Permanently").assertExists()
        rule.onNodeWithTag("confirm_button").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(confirmed && dismissed)
    }

    @Test fun `states render in every theme and accent`() {
        var mode by mutableStateOf(ThemeMode.DARK)
        var accent by mutableStateOf(AccentColor.GRASS)
        var retried = 0
        rule.setContent {
            LauncherTheme(mode, accent) {
                androidx.compose.foundation.layout.Column {
                    EmptyState(Icons.Outlined.Folder, "Empty", "Nothing here", actionLabel = "Import", onAction = {})
                    ErrorBanner("Network error", onRetry = { retried++ })
                    ProgressPanel(ProgressInfo("Downloading", 0.5f, "1 MB/s"), {})
                    SkeletonList(2)
                    Text("ok")
                }
            }
        }
        ThemeMode.entries.forEach { m -> AccentColor.entries.forEach { a -> mode = m; accent = a; rule.waitForIdle(); rule.onNodeWithTag("empty_state").assertExists() } }
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
        rule.onNodeWithTag("progress_panel").assertExists()
        rule.onNodeWithTag("skeleton").assertExists()
    }
}
