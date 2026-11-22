package com.industrial.horn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme =
    darkColorScheme(
        primary = HornAmber,
        onPrimary = HornInk,
        primaryContainer = HornAmberDeep,
        onPrimaryContainer = HornCream,
        secondary = ColorTokens.DarkSecondary,
        onSecondary = HornInk,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = ColorTokens.DarkOutline,
        error = ErrorRed,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = HornAmberDeep,
        onPrimary = HornCream,
        primaryContainer = HornAmber,
        onPrimaryContainer = HornInk,
        secondary = ColorTokens.LightSecondary,
        onSecondary = HornCream,
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = ColorTokens.LightOutline,
        error = ErrorRedLight,
    )

/**
 * Brand theme. Dynamic color is intentionally not used: the metal/amber horn identity must not
 * change with the wallpaper, and both light and dark schemes are contrast-checked.
 */
@Composable
fun HornTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = HornTypography,
        content = content,
    )
}

private object ColorTokens {
    val DarkSecondary =
        androidx.compose.ui.graphics
            .Color(0xFFB9C3CA)
    val LightSecondary =
        androidx.compose.ui.graphics
            .Color(0xFF6D6254)
    val DarkOutline =
        androidx.compose.ui.graphics
            .Color(0xFF4A555E)
    val LightOutline =
        androidx.compose.ui.graphics
            .Color(0xFF9A8E7C)
}
