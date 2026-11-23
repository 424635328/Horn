package com.industrial.horn.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.industrial.horn.audio.HornType
import com.industrial.horn.designsystem.R
import com.industrial.horn.ui.theme.HornInstrumentAccents

/**
 * Per-voice instrument identity: one accent (paired daylight/carbon so it stays ≥4.5:1 on the
 * plate) and one engraved glyph. Five voices, five instruments — never five colours of the same
 * button.
 */
data class HornVisualStyle(
    val accent: Color,
    @param:DrawableRes val iconRes: Int,
)

fun HornType.visualStyle(dark: Boolean): HornVisualStyle =
    when (this) {
        HornType.CAR -> {
            HornVisualStyle(
                accent = HornInstrumentAccents.Car.resolve(dark),
                iconRes = R.drawable.ic_type_car,
            )
        }

        HornType.TRAIN -> {
            HornVisualStyle(
                accent = HornInstrumentAccents.Train.resolve(dark),
                iconRes = R.drawable.ic_type_train,
            )
        }

        HornType.EBIKE -> {
            HornVisualStyle(
                accent = HornInstrumentAccents.EBike.resolve(dark),
                iconRes = R.drawable.ic_type_ebike,
            )
        }

        HornType.MOTORCYCLE -> {
            HornVisualStyle(
                accent = HornInstrumentAccents.Motorcycle.resolve(dark),
                iconRes = R.drawable.ic_type_motorcycle,
            )
        }

        HornType.CUSTOM -> {
            HornVisualStyle(
                accent = HornInstrumentAccents.Lab.resolve(dark),
                iconRes = R.drawable.ic_type_custom,
            )
        }
    }

/** Accent of the lab voice, used by the top-bar lab key and the sheet chrome. */
fun labAccent(dark: Boolean): Color = HornInstrumentAccents.Lab.resolve(dark)
