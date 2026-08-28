package com.mgmemo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = OnGreenPrimary,
    primaryContainer = GreenPrimaryContainer,
    onPrimaryContainer = OnGreenPrimaryContainer,
    secondary = GreenSecondary,
    onSecondary = OnGreenSecondary,
    secondaryContainer = GreenSecondaryContainer,
    onSecondaryContainer = OnGreenSecondaryContainer,
    tertiary = GreenTertiary,
    onTertiary = OnGreenTertiary,
    tertiaryContainer = GreenTertiaryContainer,
    onTertiaryContainer = OnGreenTertiaryContainer,
    error = GreenError,
    onError = OnGreenError,
    errorContainer = GreenErrorContainer,
    onErrorContainer = OnGreenErrorContainer,
    background = GreenBackground,
    onBackground = OnGreenBackground,
    surface = GreenSurface,
    onSurface = OnGreenSurface,
    surfaceVariant = GreenSurfaceVariant,
    onSurfaceVariant = OnGreenSurfaceVariant,
    outline = GreenOutline,
    outlineVariant = GreenOutlineVariant
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = OnDarkPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = OnDarkPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = OnDarkSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = OnDarkSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = OnDarkTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = OnDarkTertiaryContainer,
    error = DarkError,
    onError = OnDarkError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = OnDarkErrorContainer,
    background = DarkBackground,
    onBackground = OnDarkBackground,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

private val EyeColors = lightColorScheme(
    primary = EyePrimary,
    onPrimary = OnEyePrimary,
    primaryContainer = EyePrimaryContainer,
    onPrimaryContainer = OnEyePrimaryContainer,
    secondary = EyePrimary,
    onSecondary = OnEyePrimary,
    secondaryContainer = EyeSurfaceVariant,
    onSecondaryContainer = OnEyeSurfaceVariant,
    tertiary = EyePrimary,
    onTertiary = OnEyePrimary,
    tertiaryContainer = EyePrimaryContainer,
    onTertiaryContainer = OnEyePrimaryContainer,
    error = GreenError,
    onError = OnGreenError,
    errorContainer = GreenErrorContainer,
    onErrorContainer = OnGreenErrorContainer,
    background = EyeBackground,
    onBackground = OnEyeBackground,
    surface = EyeSurface,
    onSurface = OnEyeSurface,
    surfaceVariant = EyeSurfaceVariant,
    onSurfaceVariant = OnEyeSurfaceVariant,
    outline = EyeOutline,
    outlineVariant = EyeOutlineVariant
)

@Composable
fun MGMemoTheme(
    themeMode: String,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        "green" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when (themeMode) {
        "green" -> EyeColors
        else -> if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}