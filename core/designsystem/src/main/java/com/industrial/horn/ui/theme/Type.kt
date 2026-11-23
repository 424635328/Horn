package com.industrial.horn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Cockpit typography: a compressed grotesque for identity, and — critically — tabular figures
 * for every dynamic number (frequency, dB, ms, samples, xrun). A proportional `1` that changes
 * width while the measurement ticks is an instrument defect, not a style choice, so numeric
 * styles live in [HornText] and are reused instead of ad-hoc `fontSize` literals.
 */
val HornTypography =
    Typography(
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                letterSpacing = (-0.2).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                letterSpacing = 2.6.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                letterSpacing = 1.4.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                letterSpacing = 1.5.sp,
            ),
    )

/** Reusable cockpit text styles outside the Material 3 slots. */
object HornText {
    /** Uppercase engraved plate caption, e.g. `01 · VOICE`. */
    val PlateLabel =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            letterSpacing = 2.2.sp,
        )

    /** Keycap caption; stays legible at 135% font scale without clipping. */
    val KeyLabel =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            letterSpacing = 0.4.sp,
        )

    /** Dial / ruler numerals. Monospace + tabular so digits never jitter. */
    val Data =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = HornNumbers.TABULAR,
        )

    /** Hero readout inside the instrument pad. */
    val DataLarge =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
            fontFeatureSettings = HornNumbers.TABULAR,
        )
}

/** Tabular-figure contract; kept as a constant so no call site can typo the feature tag. */
object HornNumbers {
    const val TABULAR = "tnum"
}
