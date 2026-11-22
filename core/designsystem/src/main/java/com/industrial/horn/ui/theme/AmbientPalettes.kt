package com.industrial.horn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Per-voice ambient light palette, independently tuned for light and dark themes. Colors feed the
 * breathing mesh background behind the glass panels; they never touch foreground contrast text.
 *
 * Keys stay decoupled from the audio module so :core:designsystem keeps zero project dependencies;
 * the presentation layer maps its horn type onto these keys.
 */
@Immutable
data class AmbientPalette(
    val lightTint: Color,
    val darkTint: Color,
    val meshSoft: Color,
) {
    fun tint(dark: Boolean): Color = if (dark) darkTint else lightTint
}

enum class AmbientKey {
    CAR,
    TRAIN,
    EBIKE,
    MOTORCYCLE,
    CUSTOM,
}

object AmbientPalettes {
    /** Car: Titanium Silver (light) / Deep Sea Blue (dark). */
    val Car =
        AmbientPalette(
            lightTint = Color(0xFF8A9BA8),
            darkTint = Color(0xFF2E5F86),
            meshSoft = Color(0xFFB8C4CC),
        )

    /** Train: Amber Gold (light) / Lava Copper (dark). */
    val Train =
        AmbientPalette(
            lightTint = Color(0xFFC98F2E),
            darkTint = Color(0xFFB4562A),
            meshSoft = Color(0xFFE2B263),
        )

    /** E-Bike: Aurora Cyan (light) / Emerald Green (dark). */
    val EBike =
        AmbientPalette(
            lightTint = Color(0xFF2FA8B8),
            darkTint = Color(0xFF1F8A5C),
            meshSoft = Color(0xFF6FD6DC),
        )

    /** Motorcycle: Racing Red (light) / Blazing Orange (dark). */
    val Motorcycle =
        AmbientPalette(
            lightTint = Color(0xFFC43A32),
            darkTint = Color(0xFFD96A22),
            meshSoft = Color(0xFFE07B62),
        )

    /** Custom: Nebula Violet (light) / Iridescent Indigo (dark). */
    val Custom =
        AmbientPalette(
            lightTint = Color(0xFF7B5FC9),
            darkTint = Color(0xFF4B3FA8),
            meshSoft = Color(0xFFA78BE8),
        )

    fun of(key: AmbientKey): AmbientPalette =
        when (key) {
            AmbientKey.CAR -> Car
            AmbientKey.TRAIN -> Train
            AmbientKey.EBIKE -> EBike
            AmbientKey.MOTORCYCLE -> Motorcycle
            AmbientKey.CUSTOM -> Custom
        }
}
