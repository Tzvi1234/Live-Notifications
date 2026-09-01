package com.tzvi.kickoff.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tzvi.kickoff.core.model.AppSettings

private val KickoffLightColors = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    inversePrimary = inversePrimaryLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    surfaceTint = surfaceTintLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    surfaceBright = surfaceBrightLight,
    surfaceDim = surfaceDimLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    primaryFixed = primaryFixedLight,
    primaryFixedDim = primaryFixedDimLight,
    onPrimaryFixed = onPrimaryFixedLight,
    onPrimaryFixedVariant = onPrimaryFixedVariantLight,
    secondaryFixed = secondaryFixedLight,
    secondaryFixedDim = secondaryFixedDimLight,
    onSecondaryFixed = onSecondaryFixedLight,
    onSecondaryFixedVariant = onSecondaryFixedVariantLight,
    tertiaryFixed = tertiaryFixedLight,
    tertiaryFixedDim = tertiaryFixedDimLight,
    onTertiaryFixed = onTertiaryFixedLight,
    onTertiaryFixedVariant = onTertiaryFixedVariantLight,
)

private val KickoffDarkColors = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    inversePrimary = inversePrimaryDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    surfaceTint = surfaceTintDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    surfaceBright = surfaceBrightDark,
    surfaceDim = surfaceDimDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    primaryFixed = primaryFixedDark,
    primaryFixedDim = primaryFixedDimDark,
    onPrimaryFixed = onPrimaryFixedDark,
    onPrimaryFixedVariant = onPrimaryFixedVariantDark,
    secondaryFixed = secondaryFixedDark,
    secondaryFixedDim = secondaryFixedDimDark,
    onSecondaryFixed = onSecondaryFixedDark,
    onSecondaryFixedVariant = onSecondaryFixedVariantDark,
    tertiaryFixed = tertiaryFixedDark,
    tertiaryFixedDim = tertiaryFixedDimDark,
    onTertiaryFixed = onTertiaryFixedDark,
    onTertiaryFixedVariant = onTertiaryFixedVariantDark,
)

/**
 * Colours that carry meaning rather than hierarchy, and so must not be swapped out by
 * Material You. A red card is red on every wallpaper.
 */
data class KickoffAccents(
    val live: Color,
    val onLive: Color,
    val goal: Color,
    val yellowCard: Color,
    val redCard: Color,
    val pitch: Color,
    val pitchLine: Color,
    val win: Color,
    val draw: Color,
    val loss: Color,
)

private val LightAccents = KickoffAccents(
    live = Color(0xFFD32F2F),
    onLive = Color(0xFFFFFFFF),
    goal = Color(0xFF00A344),
    yellowCard = Color(0xFFE8B400),
    redCard = Color(0xFFD32F2F),
    pitch = Color(0xFF1E5B32),
    pitchLine = Color(0x66FFFFFF),
    win = Color(0xFF00A344),
    draw = Color(0xFF8A9187),
    loss = Color(0xFFD32F2F),
)

private val DarkAccents = LightAccents.copy(
    live = Color(0xFFFF5A52),
    goal = Color(0xFF3FE56C),
    yellowCard = Color(0xFFFFD246),
    redCard = Color(0xFFFF5A52),
    pitch = Color(0xFF123D21),
    win = Color(0xFF3FE56C),
    draw = Color(0xFF9AA396),
    loss = Color(0xFFFF5A52),
)

val LocalKickoffAccents = staticCompositionLocalOf { LightAccents }

object KickoffTheme {
    val accents: KickoffAccents
        @Composable @ReadOnlyComposable get() = LocalKickoffAccents.current
}

@Composable
fun KickoffTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Off by default. Wallpaper-derived hues fight the club colours that are the
     * actual content of this app, so Material You is opt-in from Settings.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> KickoffDarkColors
        else -> KickoffLightColors
    }
    CompositionLocalProvider(
        LocalKickoffAccents provides if (darkTheme) DarkAccents else LightAccents,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KickoffTypography,
            shapes = KickoffShapes,
            content = content,
        )
    }
}

/** Resolves the user's stored theme preference into `darkTheme`. */
@Composable
fun shouldUseDarkTheme(preference: AppSettings.DarkThemePreference): Boolean = when (preference) {
    AppSettings.DarkThemePreference.SYSTEM -> isSystemInDarkTheme()
    AppSettings.DarkThemePreference.LIGHT -> false
    AppSettings.DarkThemePreference.DARK -> true
}
