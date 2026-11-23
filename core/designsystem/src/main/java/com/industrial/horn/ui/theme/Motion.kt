package com.industrial.horn.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mechanical motion language for the cockpit.
 *
 * The reference is a real mechanism, not a bounce: a machined key travels a short distance and
 * stops. Springs are therefore critically damped or very close to it (ζ ≥ 0.88) except the
 * gauge needle, which is allowed one overshoot because a moving-coil needle physically does.
 * No linear tween is used for any interactive state change.
 */
object HornMotion {
    /** Keycap press/release: short travel, no visible overshoot. */
    val KeySpring =
        spring<Float>(
            dampingRatio = 0.90f,
            stiffness = 1_500f,
        )

    /** Instrument selector travel (voice strip underline, scene marker). */
    val IndexSpring =
        spring<Float>(
            dampingRatio = 0.88f,
            stiffness = 1_050f,
        )

    /** Gauge needle: one restrained overshoot, settles fast. */
    val NeedleSpring =
        spring<Float>(
            dampingRatio = 0.62f,
            stiffness = 520f,
        )

    /** Sheet / panel expansion. */
    val PanelSpring =
        spring<Float>(
            dampingRatio = 0.92f,
            stiffness = 620f,
        )

    /** Stiffness used by the fade of a transient warning rail. */
    val RailSpring =
        spring<Float>(
            dampingRatio = 1.0f,
            stiffness = 900f,
        )
}

/**
 * Milled-edge radius scale. Cockpit plates are near-rectangular: a pill would read as consumer
 * glass, which is exactly the language this generation removes. Nesting follows the concentric
 * identity `outer = inner + padding`.
 */
object HornRadii {
    /** Largest plate (transport deck, lab sheet). */
    val Plate: Dp = 16.dp

    /** Standard instrument plate. */
    val Instrument: Dp = 12.dp

    /** Keycap. */
    val Key: Dp = 9.dp

    /** Small badge / chip. */
    val Chip: Dp = 7.dp

    /** Recessed trough (slider wells). */
    val Well: Dp = 5.dp

    /** Concentric-radius identity. */
    fun outerOf(
        inner: Dp,
        padding: Dp,
    ): Dp = inner + padding
}

/** System "reduce motion" switch: ambient sweeps and spring travel collapse to static. */
val LocalReducedMotion = compositionLocalOf { false }
