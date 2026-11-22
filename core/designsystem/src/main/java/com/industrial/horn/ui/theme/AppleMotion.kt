package com.industrial.horn.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Apple-style spring motion presets. Interactive state changes MUST NOT use linear tweens;
 * every preset here is a damped mass-spring system so transitions feel physical.
 */
object AppleMotion {
    /** High-response switching: segmented pills, toggles, chip selection. */
    val SnappySpring =
        spring<Float>(
            dampingRatio = 0.72f,
            stiffness = 900f,
        )

    /** Fluid expansion: banners, bottom sheets, panel growth. */
    val FluidSpring =
        spring<Float>(
            dampingRatio = 0.82f,
            stiffness = 420f,
        )

    /** Tactile press with a refined, restrained overshoot: the main horn orb. */
    val TactileOrbSpring =
        spring<Float>(
            dampingRatio = 0.55f,
            stiffness = 650f,
        )

    /** Capsule travel for sliding indicators: quick but never abrupt. */
    val CapsuleSpring =
        spring<Float>(
            dampingRatio = 0.78f,
            stiffness = 620f,
        )

    /**
     * Classic iOS rubber-band resistance. Returns the visually displaced value for a raw drag
     * [offset] beyond [limit]; the curve is non-linear so the last stretch feels heavier.
     */
    fun rubberBand(
        offset: Float,
        limit: Float,
        coefficient: Float = 0.55f,
    ): Float {
        if (limit <= 0f) return 0f
        val sign = if (offset < 0f) -1f else 1f
        val magnitude = abs(offset)
        if (magnitude <= limit) return offset
        val overshoot = magnitude - limit
        return sign * (limit + (1f - 1f / (overshoot * coefficient / limit + 1f)) * limit)
    }
}

/**
 * Concentric radii law: a container radius MUST equal its inner radius plus the padding between
 * them, so nested rounded shapes share one visual curvature instead of looking misaligned.
 */
object AppleRadii {
    val Capsule: Dp = 50.dp
    val Orb: Dp = 50.dp
    val Sheet: Dp = 28.dp
    val Panel: Dp = 22.dp
    val Tile: Dp = 16.dp
    val Inner: Dp = 12.dp

    /** R_outer = R_inner + padding, the concentric-radius identity. */
    fun outerOf(
        inner: Dp,
        padding: Dp,
    ): Dp = inner + padding
}

/** Tabular-figure typography contract: dynamic numbers (Hz / ms / dB / xrun) MUST use tnum. */
object AppleNumbers {
    const val TABULAR = "tnum"
}

/**
 * System "reduce motion" accessibility switch. When true, ambient breathing and spring travel
 * collapse to static, frame-idle visuals.
 */
val LocalReducedMotion = compositionLocalOf { false }

/** Rendering capability tier for glass surfaces. Deterministic override for tests. */
enum class GlassRenderMode {
    /** API 33+: AGSL RuntimeShader edge refraction + blur backdrop. */
    AGSL,

    /** API 31-32: RenderEffect backdrop blur, gradient specular rim. */
    BLUR,

    /** API 26-30 (or test override): translucent frost + mirror rim, zero exotic effects. */
    CANVAS,
}

val LocalGlassRenderMode = compositionLocalOf { GlassRenderMode.AGSL }

/** Resolves the default tier from the platform without probing at every recomposition. */
@Composable
fun rememberDefaultGlassRenderMode(): GlassRenderMode {
    val provided = LocalGlassRenderMode.current
    return provided
}
