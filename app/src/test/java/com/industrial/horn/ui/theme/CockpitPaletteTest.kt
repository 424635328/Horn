package com.industrial.horn.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Machine-verifiable legibility gate for the Precision Cockpit palette.
 *
 * The point of a palette test is that it can fail: [metricCanGoRed] is a negative control proving
 * the WCAG computation rejects a known-bad pair (brand amber text on a white plate, 1.76:1), so a
 * future "all green" run cannot be the result of a broken metric silently returning 21:1.
 *
 * Thresholds: WCAG 2.2 AA — 4.5:1 body/micro text, 4.5:1 instrument accents used as labels, 3:1
 * for large text and non-text state markers.
 */
class CockpitPaletteTest {
    private val palettes = listOf("daylight" to DaylightColors, "carbon" to CarbonColors)

    @Test
    fun primaryTextMeetsAaOnCanvasAndPanel() {
        palettes.forEach { (name, palette) ->
            assertAtLeast(4.5, palette.textPrimary, palette.canvas, "$name primary/canvas")
            assertAtLeast(4.5, palette.textPrimary, palette.panel, "$name primary/panel")
            assertAtLeast(4.5, palette.textPrimary, palette.panelSunken, "$name primary/sunken")
        }
    }

    @Test
    fun secondaryTextMeetsAaEverywhereItIsUsed() {
        palettes.forEach { (name, palette) ->
            assertAtLeast(4.5, palette.textSecondary, palette.canvas, "$name secondary/canvas")
            assertAtLeast(4.5, palette.textSecondary, palette.panel, "$name secondary/panel")
        }
    }

    @Test
    fun tertiaryTextMeetsAaOnPlates() {
        // Tertiary is the engraved-micro-label colour and only ever sits on a plate; its canvas
        // ratio is documented as decorative (>= 3:1) instead of pretending it is body text.
        palettes.forEach { (name, palette) ->
            assertAtLeast(4.5, palette.textTertiary, palette.panel, "$name tertiary/panel")
            assertAtLeast(3.0, palette.textTertiary, palette.canvas, "$name tertiary/canvas")
            assertAtLeast(3.0, palette.textTertiary, palette.panelSunken, "$name tertiary/sunken")
        }
    }

    @Test
    fun everyInstrumentAccentMeetsAaOnItsPlate() {
        palettes.forEach { (name, palette) ->
            val accents =
                listOf(
                    "car" to HornInstrumentAccents.Car,
                    "train" to HornInstrumentAccents.Train,
                    "ebike" to HornInstrumentAccents.EBike,
                    "motorcycle" to HornInstrumentAccents.Motorcycle,
                    "lab" to HornInstrumentAccents.Lab,
                )
            accents.forEach { (voice, accent) ->
                val resolved = accent.resolve(palette.isDark)
                assertAtLeast(4.5, resolved, palette.panel, "$name $voice/panel")
                assertAtLeast(4.5, resolved, palette.canvas, "$name $voice/canvas")
            }
        }
    }

    @Test
    fun signalColoursMeetAaOnBothSurfaces() {
        palettes.forEach { (name, palette) ->
            assertAtLeast(4.5, palette.fault, palette.panel, "$name fault/panel")
            assertAtLeast(4.5, palette.fault, palette.canvas, "$name fault/canvas")
            assertAtLeast(4.5, palette.protection, palette.panel, "$name protection/panel")
            assertAtLeast(4.5, palette.protection, palette.canvas, "$name protection/canvas")
        }
    }

    @Test
    fun instrumentAccentsAreMutuallyDistinguishable() {
        // Colour is a redundant signal (label + index bar), but five voices sharing two colours
        // would still be a defect: require a minimum sRGB distance between any two accents.
        listOf(false, true).forEach { dark ->
            val accents =
                listOf(
                    HornInstrumentAccents.Car.resolve(dark),
                    HornInstrumentAccents.Train.resolve(dark),
                    HornInstrumentAccents.EBike.resolve(dark),
                    HornInstrumentAccents.Motorcycle.resolve(dark),
                    HornInstrumentAccents.Lab.resolve(dark),
                )
            for (first in accents.indices) {
                for (second in first + 1 until accents.size) {
                    val distance = rgbDistance(accents[first], accents[second])
                    assertTrue(
                        "instrument accents $first and $second are too close in ${if (dark) "carbon" else "daylight"} " +
                            "(distance ${"%.1f".format(distance)} < 48)",
                        distance >= 48.0,
                    )
                }
            }
        }
    }

    @Test
    fun metricCanGoRed() {
        // Negative control: the previous generation's amber-on-white combination.
        val amber = HornInstrumentAccents.Car.carbon
        val white = Color(0xFFFFFFFF)
        val bad = contrast(amber, white)
        assertTrue(
            "the WCAG metric must reject amber-on-white, got ${"%.2f".format(bad)}",
            bad < 3.0,
        )
    }

    @Test
    fun metricHasAKnownUpperBound() {
        // Positive control: black on white is the 21:1 anchor of the WCAG formula.
        val ratio = contrast(Color(0xFF000000), Color(0xFFFFFFFF))
        assertTrue("black/white must be ~21:1, got ${"%.2f".format(ratio)}", abs(ratio - 21.0) < 0.05)
    }

    private fun assertAtLeast(
        minimum: Double,
        foreground: Color,
        background: Color,
        label: String,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$label must reach $minimum:1, got ${"%.2f".format(ratio)}:1",
            ratio >= minimum,
        )
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val lighter = max(a, b)
        val darker = min(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun rgbDistance(
        first: Color,
        second: Color,
    ): Double {
        val dr = (first.red - second.red).toDouble() * 255.0
        val dg = (first.green - second.green).toDouble() * 255.0
        val db = (first.blue - second.blue).toDouble() * 255.0
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }
}
