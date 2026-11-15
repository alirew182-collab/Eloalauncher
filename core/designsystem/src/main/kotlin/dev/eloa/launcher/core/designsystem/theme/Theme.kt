package dev.eloa.launcher.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Accent palettes. Each defines the seed color used to derive the scheme. */
enum class AccentColor(val seed: Color, val label: String) {
    GRASS(Color(0xFF4CAF50), "Grass"),
    DIAMOND(Color(0xFF26C6DA), "Diamond"),
    REDSTONE(Color(0xFFE53935), "Redstone"),
    GOLD(Color(0xFFFFB300), "Gold"),
    AMETHYST(Color(0xFF9C6BFF), "Amethyst"),
    LAPIS(Color(0xFF3F6BFF), "Lapis"),
    DYNAMIC(Color(0xFF4CAF50), "System (Material You)"),
}

@Immutable
data class LauncherExtras(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val heroGradient: List<Color>,
    val cardGradient: List<Color>,
    val enableBlur: Boolean,
    val reduceMotion: Boolean,
)

val LocalLauncherExtras = staticCompositionLocalOf {
    LauncherExtras(Color.Green, Color.Yellow, Color.Red, listOf(Color.Black, Color.DarkGray), listOf(Color.DarkGray, Color.Black), false, false)
}

private fun schemeFor(accent: Color, dark: Boolean): ColorScheme {
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF10140F) else Color.White
    return if (dark) {
        darkColorScheme(
            primary = lerp(accent, Color.White, 0.15f),
            onPrimary = Color(0xFF0B0F0A),
            primaryContainer = lerp(accent, Color.Black, 0.55f),
            onPrimaryContainer = lerp(accent, Color.White, 0.8f),
            secondary = lerp(accent, Color(0xFF9AA59A), 0.6f),
            secondaryContainer = Color(0xFF2A302B),
            onSecondaryContainer = Color(0xFFDCE5DA),
            tertiary = Color(0xFF7FD6E8),
            background = Color(0xFF0E110F),
            onBackground = Color(0xFFE2E6E0),
            surface = Color(0xFF121613),
            onSurface = Color(0xFFE2E6E0),
            surfaceVariant = Color(0xFF232924),
            onSurfaceVariant = Color(0xFFBFC8BD),
            surfaceContainerLowest = Color(0xFF0A0D0B),
            surfaceContainerLow = Color(0xFF161B17),
            surfaceContainer = Color(0xFF1A201B),
            surfaceContainerHigh = Color(0xFF222923),
            surfaceContainerHighest = Color(0xFF2B332C),
            outline = Color(0xFF889287),
            outlineVariant = Color(0xFF3E463F),
            error = Color(0xFFFF6B6B),
        )
    } else {
        lightColorScheme(
            primary = lerp(accent, Color.Black, 0.2f),
            onPrimary = onAccent.takeIf { accent.luminance() < 0.5f } ?: Color.White,
            primaryContainer = lerp(accent, Color.White, 0.75f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.7f),
            secondary = lerp(accent, Color(0xFF55605A), 0.65f),
            secondaryContainer = Color(0xFFDDE6DA),
            tertiary = Color(0xFF00677A),
            background = Color(0xFFF6F8F3),
            onBackground = Color(0xFF181D18),
            surface = Color(0xFFFBFDF8),
            onSurface = Color(0xFF181D18),
            surfaceVariant = Color(0xFFE0E6DC),
            onSurfaceVariant = Color(0xFF424940),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF2F5EE),
            surfaceContainer = Color(0xFFECF0E8),
            surfaceContainerHigh = Color(0xFFE6EBE2),
            surfaceContainerHighest = Color(0xFFE0E5DC),
            outline = Color(0xFF727970),
            outlineVariant = Color(0xFFC2C9BE),
        )
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private val LauncherTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun LauncherTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.GRASS,
    enableBlur: Boolean = true,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val scheme = if (accent == AccentColor.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else schemeFor(accent.seed, dark)
    val extras = LauncherExtras(
        success = if (dark) Color(0xFF6EDC7A) else Color(0xFF1E7B34),
        warning = if (dark) Color(0xFFFFC857) else Color(0xFF8A5A00),
        danger = scheme.error,
        heroGradient = if (dark) listOf(lerp(scheme.primary, Color.Black, 0.55f), Color(0xFF101512), lerp(scheme.tertiary, Color.Black, 0.75f))
        else listOf(lerp(scheme.primary, Color.White, 0.55f), Color(0xFFF7FAF4), lerp(scheme.tertiary, Color.White, 0.8f)),
        cardGradient = listOf(scheme.surfaceContainerHigh, scheme.surfaceContainer),
        enableBlur = enableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        reduceMotion = reduceMotion,
    )
    CompositionLocalProvider(LocalLauncherExtras provides extras) {
        MaterialTheme(colorScheme = scheme, typography = LauncherTypography, content = content)
    }
}
