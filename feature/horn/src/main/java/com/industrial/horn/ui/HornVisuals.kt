package com.industrial.horn.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.industrial.horn.audio.HornType
import com.industrial.horn.designsystem.R
import com.industrial.horn.ui.theme.AmbientKey
import com.industrial.horn.ui.theme.HornAmber
import com.industrial.horn.ui.theme.HornBlue
import com.industrial.horn.ui.theme.HornMint
import com.industrial.horn.ui.theme.HornSignal
import com.industrial.horn.ui.theme.HornViolet

/** Maps a horn voice onto its design-system ambient light palette key. */
fun HornType.ambientKey(): AmbientKey =
    when (this) {
        HornType.CAR -> AmbientKey.CAR
        HornType.TRAIN -> AmbientKey.TRAIN
        HornType.EBIKE -> AmbientKey.EBIKE
        HornType.MOTORCYCLE -> AmbientKey.MOTORCYCLE
        HornType.CUSTOM -> AmbientKey.CUSTOM
    }

/** Per-voice visual language: accent, central icon and acoustic-inspired wave geometry. */
data class HornVisualStyle(
    val accent: Color,
    @param:DrawableRes val iconRes: Int,
    val wave: WaveStyle,
)

data class WaveStyle(
    val ringCount: Int,
    val durationMillis: Int,
    val expansion: Float,
    val strokeWidthDp: Float,
    val baseAlpha: Float,
    val haloAlpha: Float,
)

fun HornType.visualStyle(): HornVisualStyle =
    when (this) {
        HornType.CAR -> {
            HornVisualStyle(
                accent = HornAmber,
                iconRes = R.drawable.ic_type_car,
                wave =
                    WaveStyle(
                        ringCount = 3,
                        durationMillis = 1_700,
                        expansion = 0.55f,
                        strokeWidthDp = 1.6f,
                        baseAlpha = 0.30f,
                        haloAlpha = 0.035f,
                    ),
            )
        }

        HornType.TRAIN -> {
            HornVisualStyle(
                accent = HornSignal,
                iconRes = R.drawable.ic_type_train,
                wave =
                    WaveStyle(
                        ringCount = 4,
                        durationMillis = 2_400,
                        expansion = 0.78f,
                        strokeWidthDp = 3.0f,
                        baseAlpha = 0.22f,
                        haloAlpha = 0.065f,
                    ),
            )
        }

        HornType.EBIKE -> {
            HornVisualStyle(
                accent = HornMint,
                iconRes = R.drawable.ic_type_ebike,
                wave =
                    WaveStyle(
                        ringCount = 5,
                        durationMillis = 900,
                        expansion = 0.38f,
                        strokeWidthDp = 0.9f,
                        baseAlpha = 0.36f,
                        haloAlpha = 0.025f,
                    ),
            )
        }

        HornType.MOTORCYCLE -> {
            HornVisualStyle(
                accent = HornBlue,
                iconRes = R.drawable.ic_type_motorcycle,
                wave =
                    WaveStyle(
                        ringCount = 3,
                        durationMillis = 1_250,
                        expansion = 0.46f,
                        strokeWidthDp = 1.3f,
                        baseAlpha = 0.30f,
                        haloAlpha = 0.04f,
                    ),
            )
        }

        HornType.CUSTOM -> {
            HornVisualStyle(
                accent = HornViolet,
                iconRes = R.drawable.ic_type_custom,
                wave =
                    WaveStyle(
                        ringCount = 4,
                        durationMillis = 1_400,
                        expansion = 0.52f,
                        strokeWidthDp = 1.4f,
                        baseAlpha = 0.32f,
                        haloAlpha = 0.045f,
                    ),
            )
        }
    }
