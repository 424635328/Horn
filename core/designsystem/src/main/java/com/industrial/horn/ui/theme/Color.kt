package com.industrial.horn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Precision Automotive Cockpit palette (v4.0).
 *
 * Two material stories, one instrument language:
 *  - **Ceramic Daylight** — matte ceramic canvas, white machined plates, graphite hairline
 *    engraving. Reads like a physical dashboard in daylight: no translucency, no blur, no glow.
 *  - **Stealth Carbon** — carbon-black canvas, anthracite plates, one step of raised metal.
 *
 * Colours are frozen here and nowhere else. Every pair that carries text was contrast-checked
 * against WCAG 2.2 AA in `app/src/test/java/com/industrial/horn/ui/theme/CockpitPaletteTest.kt`
 * (body 4.5:1, large/micro-label 3:1, non-text instrument marks 3:1).
 */

// ---------------------------------------------------------------------------------------------
// Ceramic Daylight
// ---------------------------------------------------------------------------------------------
val CeramicCanvas = Color(0xFFF3F3EE)
val CeramicPanel = Color(0xFFFFFFFF)
val CeramicPanelSunken = Color(0xFFEAEAE4)
val CeramicHairline = Color(0xFFDFDFD8)
val CeramicHairlineStrong = Color(0xFFC4C4BC)
val CeramicTextPrimary = Color(0xFF141619)
val CeramicTextSecondary = Color(0xFF626770)
val CeramicTextTertiary = Color(0xFF6F747C)
val CeramicGrid = Color(0xFFE2E2DA)
val CeramicMetalTop = Color(0xFFFBFBF8)
val CeramicMetalBottom = Color(0xFFE4E4DC)

// ---------------------------------------------------------------------------------------------
// Stealth Carbon
// ---------------------------------------------------------------------------------------------
val CarbonCanvas = Color(0xFF0D0F12)
val CarbonPanel = Color(0xFF16191F)
val CarbonPanelRaised = Color(0xFF1D2129)
val CarbonPanelSunken = Color(0xFF101318)
val CarbonHairline = Color(0xFF2C313C)
val CarbonHairlineStrong = Color(0xFF3C4250)
val CarbonTextPrimary = Color(0xFFF4F6F9)
val CarbonTextSecondary = Color(0xFF8C94A3)
val CarbonTextTertiary = Color(0xFF7C8493)
val CarbonGrid = Color(0xFF1C2027)
val CarbonMetalTop = Color(0xFF2A3039)
val CarbonMetalBottom = Color(0xFF12151A)

// ---------------------------------------------------------------------------------------------
// Signal colours: identical semantics in both themes.
// ---------------------------------------------------------------------------------------------
val HornFault = Color(0xFFC62E20)
val HornFaultDark = Color(0xFFFF6A5A)
val HornProtection = Color(0xFF8A5A00)
val HornProtectionDark = Color(0xFFFFC24D)

/**
 * One instrument accent per horn voice. A single mid-tone cannot clear 4.5:1 on both a white
 * plate and a carbon plate, so each voice ships a paired daylight/carbon value instead of
 * compromising legibility for brand consistency.
 */
@Immutable
data class InstrumentAccent(
    val daylight: Color,
    val carbon: Color,
) {
    fun resolve(dark: Boolean): Color = if (dark) carbon else daylight
}

object HornInstrumentAccents {
    val Car = InstrumentAccent(daylight = Color(0xFFA85B00), carbon = Color(0xFFFFB547))
    val Train = InstrumentAccent(daylight = Color(0xFFB3201A), carbon = Color(0xFFFF8A66))
    val EBike = InstrumentAccent(daylight = Color(0xFF0F766E), carbon = Color(0xFF43E3C0))
    val Motorcycle = InstrumentAccent(daylight = Color(0xFF1D4ED8), carbon = Color(0xFF82B8FF))
    val Lab = InstrumentAccent(daylight = Color(0xFF6D28D9), carbon = Color(0xFFC9A6FF))
}

/**
 * Extended cockpit tokens that Material 3's scheme cannot express (grid engraving, metal
 * gradients, unlit tick colour). Provided through [LocalHornColors]; stock M3 components keep
 * using the mapped `ColorScheme`.
 */
@Immutable
data class HornColors(
    val isDark: Boolean,
    val canvas: Color,
    val grid: Color,
    val panel: Color,
    val panelRaised: Color,
    val panelSunken: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val metalTop: Color,
    val metalBottom: Color,
    val dialTop: Color,
    val dialBottom: Color,
    val bezelTop: Color,
    val bezelBottom: Color,
    val fault: Color,
    val protection: Color,
) {
    /** Unlit ruler tick / dial graduation. */
    val graduation: Color
        get() = textTertiary

    /** Machined groove between plate and canvas. */
    val groove: Color
        get() = if (isDark) Color(0xFF090B0E) else Color(0xFFD6D6CE)

    /** Specular line on the top edge of a raised plate. */
    val plateHighlight: Color
        get() = if (isDark) Color(0xFF343B47) else Color(0xFFFFFFFF)

    val defaultAccent: Color
        get() = HornInstrumentAccents.Car.resolve(isDark)
}

val DaylightColors =
    HornColors(
        isDark = false,
        canvas = CeramicCanvas,
        grid = CeramicGrid,
        panel = CeramicPanel,
        panelRaised = Color(0xFFFFFFFF),
        panelSunken = CeramicPanelSunken,
        hairline = CeramicHairline,
        hairlineStrong = CeramicHairlineStrong,
        textPrimary = CeramicTextPrimary,
        textSecondary = CeramicTextSecondary,
        textTertiary = CeramicTextTertiary,
        metalTop = CeramicMetalTop,
        metalBottom = CeramicMetalBottom,
        dialTop = Color(0xFFF0F0E9),
        dialBottom = Color(0xFFD5D5CD),
        bezelTop = Color(0xFFEDEDE6),
        bezelBottom = Color(0xFFE0E0D8),
        fault = HornFault,
        protection = HornProtection,
    )

val CarbonColors =
    HornColors(
        isDark = true,
        canvas = CarbonCanvas,
        grid = CarbonGrid,
        panel = CarbonPanel,
        panelRaised = CarbonPanelRaised,
        panelSunken = CarbonPanelSunken,
        hairline = CarbonHairline,
        hairlineStrong = CarbonHairlineStrong,
        textPrimary = CarbonTextPrimary,
        textSecondary = CarbonTextSecondary,
        textTertiary = CarbonTextTertiary,
        metalTop = CarbonMetalTop,
        metalBottom = CarbonMetalBottom,
        dialTop = Color(0xFF2C333D),
        dialBottom = Color(0xFF12151A),
        bezelTop = Color(0xFF2A323D),
        bezelBottom = Color(0xFF1E242C),
        fault = HornFaultDark,
        protection = HornProtectionDark,
    )

val LocalHornColors = staticCompositionLocalOf { DaylightColors }
