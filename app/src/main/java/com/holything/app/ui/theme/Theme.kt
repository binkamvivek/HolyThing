package com.holything.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val LightColorScheme = lightColorScheme(
    primary = SlateNavyLight,
    onPrimary = SurfaceLinenLight,
    primaryContainer = SurfaceVariantLight,
    onPrimaryContainer = TextCharcoalLight,
    secondary = SageOliveLight,
    onSecondary = SurfaceLinenLight,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = TextCharcoalLight,
    tertiary = WarmSandLight,
    background = BackgroundLinenLight,
    onBackground = TextCharcoalLight,
    surface = SurfaceLinenLight,
    onSurface = TextCharcoalLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextMutedLight
)

private val DarkColorScheme = darkColorScheme(
    primary = SlateNavyDark,
    onPrimary = BackgroundNightDark,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = TextCreamDark,
    secondary = SageOliveDark,
    onSecondary = BackgroundNightDark,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = TextCreamDark,
    tertiary = WarmSandDark,
    background = BackgroundNightDark,
    onBackground = TextCreamDark,
    surface = SurfaceNightDark,
    onSurface = TextCreamDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMutedDark
)

@Composable
fun HolyThingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    textScaleFactor: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val currentDensity = LocalDensity.current
    
    // Scale all fonts dynamically using user-selected text scale multiplier
    val customDensity = Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale * textScaleFactor
    )

    CompositionLocalProvider(LocalDensity provides customDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HolyThingTypography,
            content = content
        )
    }
}

