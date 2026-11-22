package dev.eloa.launcher

import android.graphics.Bitmap
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.feature.java.ProfileConfig
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.nav.Destination
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.inject.Inject

/** Renders key screens to PNG (build/screenshots) for visual review. Not an assertion test. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest : UiTestBase() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var profiles: ProfileRepository

    private fun shot(name: String) {
        settle()
        var bmp: Bitmap? = null
        compose.runOnUiThread {
            val v = compose.activity.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        val out = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `capture screens`() {
        runBlocking { profiles.create(ProfileConfig(name = "Fabric Survival", minecraftVersion = "1.21.4", loader = "FABRIC")) }
        listOf("DARK", "LIGHT").forEach { mode ->
            runBlocking { settings.update { it.copy(themeMode = mode) } }
            compose.waitTag("home"); shot("home_${mode.lowercase()}")
        }
        runBlocking { settings.update { it.copy(themeMode = "DARK") } }
        listOf(Destination.PERFORMANCE, Destination.WORLDS, Destination.ACCOUNTS, Destination.LIBRARY).forEach { d -> go(d); shot(d.route) }
    }
}
