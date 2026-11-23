package com.industrial.horn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val CarbonScheme =
    darkColorScheme(
        primary = HornInstrumentAccents.Car.carbon,
        onPrimary = Color(0xFF0F1115),
        primaryContainer = CarbonPanelRaised,
        onPrimaryContainer = CarbonTextPrimary,
        secondary = CarbonTextSecondary,
        onSecondary = CarbonCanvas,
        background = CarbonCanvas,
        onBackground = CarbonTextPrimary,
        surface = CarbonPanel,
        onSurface = CarbonTextPrimary,
        surfaceVariant = CarbonPanelRaised,
        onSurfaceVariant = CarbonTextSecondary,
        surfaceContainer = CarbonPanel,
        surfaceContainerHigh = CarbonPanelRaised,
        surfaceContainerLow = CarbonPanelSunken,
        outline = CarbonHairlineStrong,
        outlineVariant = CarbonHairline,
        error = HornFaultDark,
        onError = CarbonCanvas,
    )

private val CeramicScheme =
    lightColorScheme(
        primary = HornInstrumentAccents.Car.daylight,
        onPrimary = CeramicPanel,
        primaryContainer = CeramicPanelSunken,
        onPrimaryContainer = CeramicTextPrimary,
        secondary = CeramicTextSecondary,
        onSecondary = CeramicPanel,
        background = CeramicCanvas,
        onBackground = CeramicTextPrimary,
        surface = CeramicPanel,
        onSurface = CeramicTextPrimary,
        surfaceVariant = CeramicPanelSunken,
        onSurfaceVariant = CeramicTextSecondary,
        surfaceContainer = CeramicPanel,
        surfaceContainerHigh = CeramicPanel,
        surfaceContainerLow = CeramicPanelSunken,
        outline = CeramicHairlineStrong,
        outlineVariant = CeramicHairline,
        error = HornFault,
        onError = CeramicPanel,
    )

/**
 * Cockpit theme. Dynamic colour is deliberately not used: the material identity (ceramic or
 * carbon) is part of the product, and both schemes are contrast-checked by a JVM test, so a
 * wallpaper cannot repaint the instrument or push it below AA.
 *
 * [darkTheme] must be resolved by the host from the persisted [ThemeMode] — see
 * `MainActivity`, which feeds `ThemeMode.SYSTEM` from `isSystemInDarkTheme()` and pins the other
 * two modes.
 */
@Composable
fun HornTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHornColors provides if (darkTheme) CarbonColors else DaylightColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) CarbonScheme else CeramicScheme,
            typography = HornTypography,
            content = content,
        )
    }
}
