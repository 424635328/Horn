package com.industrial.horn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optical Wake startup timeline. Everything on screen is a pure function f(t) of one elapsed
 * clock — no per-element scheduling — so any frame can be reproduced, scrubbed or snapshotted.
 * Frozen milestones (milliseconds):
 *
 *  0–160   WAKE   : orb silhouette 0.62 → 0.72, opacity 0 → 1
 *  160–500  FOCUS  : twin halo rings sweep in (60 ms stagger), specular sweep, mesh wash bleeds
 *  500–820  BLOOM  : orb settles to 1.0 with a TactileOrb-style overshoot, two amber pressure
 *                    arcs emit, three star glints pulse, wordmark settles; one light haptic at 560
 *  820–1040 MERGE  : the reveal latch fires once at 820 (main UI takes over underneath) and the
 *                    overlay scales 1.0 → 1.04 while fading out; it then leaves composition
 */
object StartupTimeline {
    const val TOTAL_MS = 1040f
    const val WAKE_END_MS = 160f
    const val FOCUS_END_MS = 500f
    const val BLOOM_END_MS = 820f
    const val MERGE_END_MS = TOTAL_MS
    const val REVEAL_LATCH_MS = BLOOM_END_MS
    const val HAPTIC_MS = 560f
    const val SKIP_COLLAPSE_MS = 120f
    const val CONSOLE_STAGGER_MS = 40f

    fun wakeAlpha(t: Float): Float = smoothstep(0f, WAKE_END_MS, t)

    fun wakeScale(t: Float): Float = 0.62f + 0.10f * smoothstep(0f, WAKE_END_MS, t)

    fun focusProgress(t: Float): Float = smoothstep(WAKE_END_MS, FOCUS_END_MS, t)

    fun ringSweep(
        t: Float,
        ringIndex: Int,
    ): Float = smoothstep(WAKE_END_MS + ringIndex * 60f, FOCUS_END_MS - 40f + ringIndex * 60f, t)

    fun bloomProgress(t: Float): Float = smoothstep(FOCUS_END_MS, BLOOM_END_MS, t)

    fun mergeProgress(t: Float): Float = smoothstep(BLOOM_END_MS, MERGE_END_MS, t)

    fun overlayAlpha(t: Float): Float = 1f - mergeProgress(t)

    fun overlayScale(t: Float): Float = 1f + 0.04f * mergeProgress(t)

    /** Orb scale: wake ramp, then a spring-settled bloom to full size. */
    fun orbScale(t: Float): Float =
        if (t < FOCUS_END_MS) {
            wakeScale(t)
        } else {
            0.72f + 0.28f * settle(bloomProgress(t))
        }

    /** Pressure-arc phase loop (%1) used by the two amber emission arcs. */
    fun pressurePhase(t: Float): Float = (((t - FOCUS_END_MS) / 260f) % 1f + 1f) % 1f

    /** Star glint pulses (ring-crossing sparkle motif), one window per glint. */
    fun glintPulse(
        t: Float,
        index: Int,
    ): Float {
        val start = 520f + index * 85f
        return smoothstep(start, start + 60f, t) * (1f - smoothstep(start + 120f, start + 260f, t))
    }
}

/**
 * Staggered reveal progress for main-UI islands during the MERGE handoff: each island rises with
 * a spring settle, offset from the previous one by [STAGGER_STEP] of the reveal window (the
 * frozen 40 ms stagger of the 220 ms merge). Returns 1 for a fully entered island, so callers
 * can default the provider to "already revealed" and stay pixel-identical outside startup.
 */
fun revealStagger(
    reveal: Float,
    index: Int,
): Float {
    val start = index * STAGGER_STEP
    return settle(smoothstep(start, start + STAGGER_SPAN, reveal))
}

private const val STAGGER_STEP = 0.18f
private const val STAGGER_SPAN = 0.34f

/** Classic smoothstep — the single easing primitive of the whole timeline. */
fun smoothstep(
    edge0: Float,
    edge1: Float,
    x: Float,
): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Deterministic underdamped step response (TactileOrb character): ~12% overshoot that settles
 * exactly to 1 — a milestone curve derived from physics, not an easing library.
 */
fun settle(p: Float): Float {
    if (p <= 0f) return 0f
    if (p >= 1f) return 1f
    val zeta = 0.55
    val omega = 9.0
    val omegaD = omega * sqrt(1.0 - zeta * zeta)
    val decay = exp(-zeta * omega * p)
    return (1.0 - decay * (cos(omegaD * p) + (zeta * omega / omegaD) * sin(omegaD * p))).toFloat()
}

/** Precomputed gradient work for one wake scene; built once per size/colour change. */
@Immutable
class WakeSceneBrushes(
    val wash: Brush,
    val orbBody: Brush,
    val orbGlow: Brush,
) {
    companion object {
        fun of(
            size: Size,
            center: Offset,
            radius: Float,
            deep: Color,
            warm: Color,
            dark: Boolean,
        ): WakeSceneBrushes =
            WakeSceneBrushes(
                wash =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                warm.copy(alpha = if (dark) 0.16f else 0.10f),
                                deep.copy(alpha = if (dark) 0.22f else 0.12f),
                                Color.Transparent,
                            ),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = maxOf(size.width, size.height) * 0.72f,
                    ),
                orbBody =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                if (dark) Color(0xFF3A444D) else Color(0xFFF2F5F8),
                                if (dark) Color(0xFF12181D) else Color(0xFFB9C4CD),
                            ),
                        center = Offset(center.x - radius * 0.32f, center.y - radius * 0.38f),
                        radius = radius * 1.6f,
                    ),
                orbGlow =
                    Brush.radialGradient(
                        colors = listOf(warm.copy(alpha = 0.34f), Color.Transparent),
                        center = center,
                        radius = radius * 1.45f,
                    ),
            )
    }
}

/**
 * Draws one wake frame. Zero allocation per call: every brush was precomputed in
 * [WakeSceneBrushes], every remaining primitive is scalar math over solid colours.
 */
fun DrawScope.drawWakeScene(
    t: Float,
    brushes: WakeSceneBrushes,
    accent: Color,
    deep: Color,
    warm: Color,
) {
    val center = Offset(size.width / 2f, size.height * 0.42f)
    val radius = min(size.width, size.height) * 0.24f
    val alpha = StartupTimeline.wakeAlpha(t)
    val scale = StartupTimeline.orbScale(t)
    val r = radius * scale

    // Ambient wash bleeding in behind the orb.
    drawRect(brush = brushes.wash, alpha = alpha)

    // Twin halo rings sweeping in with a 60 ms stagger.
    for (ring in 0..1) {
        val sweep = StartupTimeline.ringSweep(t, ring)
        if (sweep <= 0f) continue
        val ringRadius = r * (1.22f + ring * 0.16f)
        drawArc(
            color = (if (ring == 0) Color.White else warm).copy(alpha = (0.34f - ring * 0.12f) * sweep * alpha),
            startAngle = -90f,
            sweepAngle = 360f * sweep,
            useCenter = false,
            topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
            size = Size(ringRadius * 2, ringRadius * 2),
            style = Stroke(width = (2.2f - ring * 0.7f) * density * 0.5f),
        )
    }

    // Orb glow + body + accent rim.
    drawCircle(brush = brushes.orbGlow, radius = r * 1.45f, center = center, alpha = alpha)
    drawCircle(brush = brushes.orbBody, radius = r, center = center, alpha = alpha)
    drawCircle(
        color = accent.copy(alpha = 0.42f * alpha),
        radius = r,
        center = center,
        style = Stroke(width = 1.5f * density * 0.5f),
    )

    // Specular sweep across the crown during FOCUS.
    val focus = StartupTimeline.focusProgress(t)
    if (focus > 0f && focus < 1f) {
        rotate(degrees = -18f + 36f * focus, pivot = center) {
            clipRect(
                left = center.x - r,
                top = center.y - r,
                right = center.x + r,
                bottom = center.y + r,
            ) {
                val sweepX = center.x - r + 2f * r * focus
                drawLine(
                    color = Color.White.copy(alpha = 0.35f * alpha),
                    start = Offset(sweepX - r * 0.18f, center.y - r),
                    end = Offset(sweepX + r * 0.18f, center.y + r),
                    strokeWidth = r * 0.09f,
                )
            }
        }
    }

    // Two amber pressure arcs emitting from the crown during BLOOM.
    val bloom = StartupTimeline.bloomProgress(t)
    if (bloom > 0f) {
        val phase = StartupTimeline.pressurePhase(t)
        for (arc in 0..1) {
            val p = (phase + arc * 0.5f) % 1f
            val arcRadius = r * (1.32f + p * 0.55f)
            drawArc(
                color = warm.copy(alpha = (1f - p) * 0.55f * bloom * alpha),
                startAngle = -58f,
                sweepAngle = 116f,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = 2.6f * density * 0.5f),
            )
        }
    }

    // Three star glints (design-prototype motif) pulsing at the ring crossings.
    for (glint in 0..2) {
        val pulse = StartupTimeline.glintPulse(t, glint)
        if (pulse <= 0f) continue
        val angle = (-58f + glint * 58f) * (PI.toFloat() / 180f)
        val gr = r * 1.52f
        val gx = center.x + cos(angle) * gr
        val gy = center.y + sin(angle) * gr
        val arm = r * 0.14f * pulse
        val glintColor = if (glint == 1) deep else warm
        drawLine(glintColor.copy(alpha = 0.85f * pulse * alpha), Offset(gx - arm, gy), Offset(gx + arm, gy), 1.6f * density * 0.5f)
        drawLine(glintColor.copy(alpha = 0.85f * pulse * alpha), Offset(gx, gy - arm), Offset(gx, gy + arm), 1.6f * density * 0.5f)
    }
}
