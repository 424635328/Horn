package com.industrial.horn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cold-start calibration timeline. Everything on screen is a pure function f(t) of one elapsed
 * clock — no per-element scheduling — so any frame can be reproduced, scrubbed or snapshotted.
 * Frozen milestones (milliseconds):
 *
 *  0–160   WAKE   : instrument silhouette 0.62 → 0.72, opacity 0 → 1
 *  160–500  FOCUS  : caliper crosshair extends, 60 graduations sweep in clockwise (60 ms
 *                    stagger), specular pass over the bezel
 *  500–820  BLOOM  : dial settles to 1.0 with a damped overshoot, two accent pressure arcs emit,
 *                    three calibration points flash, wordmark settles; one light haptic at 560
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

    /** Dial scale: wake ramp, then a spring-settled bloom to full size. */
    fun orbScale(t: Float): Float =
        if (t < FOCUS_END_MS) {
            wakeScale(t)
        } else {
            0.72f + 0.28f * settle(bloomProgress(t))
        }

    /** Pressure-arc phase loop (%1) used by the two accent emission arcs. */
    fun pressurePhase(t: Float): Float = (((t - FOCUS_END_MS) / 260f) % 1f + 1f) % 1f

    /** Calibration-point flashes, one window per point. */
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
 * Deterministic underdamped step response (machined dial character): ~12% overshoot that settles
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
    val dialBody: Brush,
    val dialGlow: Brush,
) {
    companion object {
        fun of(
            size: Size,
            center: Offset,
            radius: Float,
            deep: Color,
            warm: Color,
            dark: Boolean,
            faceTop: Color,
            faceBottom: Color,
        ): WakeSceneBrushes =
            WakeSceneBrushes(
                wash =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                warm.copy(alpha = if (dark) 0.035f else 0.025f),
                                deep.copy(alpha = if (dark) 0.05f else 0.025f),
                                Color.Transparent,
                            ),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = maxOf(size.width, size.height) * 1.05f,
                    ),
                dialBody =
                    Brush.radialGradient(
                        colors = listOf(faceTop, faceBottom),
                        center = Offset(center.x - radius * 0.32f, center.y - radius * 0.38f),
                        radius = radius * 1.9f,
                    ),
                dialGlow =
                    Brush.radialGradient(
                        colors = listOf(warm.copy(alpha = 0.10f), Color.Transparent),
                        center = center,
                        radius = radius * 1.30f,
                    ),
            )
    }
}

/**
 * Draws one calibration frame. Zero allocation per call except the specular clip path, which is
 * reused through the inline [Path] built at draw time only while FOCUS is active.
 */
fun DrawScope.drawWakeScene(
    t: Float,
    brushes: WakeSceneBrushes,
    accent: Color,
    deep: Color,
    warm: Color,
    dark: Boolean,
    tickColor: Color,
) {
    val center = Offset(size.width / 2f, size.height * 0.42f)
    val radius = min(size.width, size.height) * 0.24f
    val alpha = StartupTimeline.wakeAlpha(t)
    val scale = StartupTimeline.orbScale(t)
    val r = radius * scale
    val focus = StartupTimeline.focusProgress(t)
    val bloom = StartupTimeline.bloomProgress(t)

    // Ambient wash bleeding in behind the instrument.
    drawRect(brush = brushes.wash, alpha = alpha)

    // Two accent pressure arcs emitting from the crown during BLOOM (behind the dial).
    if (bloom > 0f) {
        val phase = StartupTimeline.pressurePhase(t)
        for (arc in 0..1) {
            val p = (phase + arc * 0.5f) % 1f
            val arcRadius = r * (1.40f + p * 0.40f)
            drawArc(
                color = warm.copy(alpha = (1f - p) * 0.40f * bloom * alpha),
                startAngle = -58f,
                sweepAngle = 116f,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }

    // Dial glow + turned face.
    drawCircle(brush = brushes.dialGlow, radius = r * 1.35f, center = center, alpha = alpha)
    drawCircle(brush = brushes.dialBody, radius = r, center = center, alpha = alpha)

    // Turning marks: three concentric machining rings (fixed geometry, no animation).
    for (ring in 1..3) {
        drawCircle(
            color = tickColor.copy(alpha = 0.20f * alpha),
            radius = r * (0.36f + ring * 0.17f),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    // Knurled rim: 48 radial cuts, fixed geometry.
    for (cut in 0 until 48) {
        val angle = cut * (360f / 48f) * (PI.toFloat() / 180f)
        val cosA = cos(angle)
        val sinA = sin(angle)
        drawLine(
            color = tickColor.copy(alpha = 0.38f * alpha),
            start = Offset(center.x + cosA * r * 0.93f, center.y + sinA * r * 0.93f),
            end = Offset(center.x + cosA * r * 0.99f, center.y + sinA * r * 0.99f),
            strokeWidth = 1.dp.toPx(),
        )
    }

    // Rim: a printed edge plus a lit top-left arc, so the disc reads as metal on both palettes.
    drawCircle(
        color = (if (dark) accent else tickColor).copy(alpha = (if (dark) 0.55f else 0.85f) * alpha),
        radius = r,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawArc(
        color = if (dark) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.75f),
        startAngle = 155f,
        sweepAngle = 110f,
        useCenter = false,
        topLeft = Offset(center.x - r + 1f, center.y - r + 1f),
        size = Size((r - 1f) * 2f, (r - 1f) * 2f),
        style = Stroke(width = 1.4.dp.toPx()),
    )

    // 60 graduations sweep in clockwise; the four cardinal marks carry the accent.
    val sweep = StartupTimeline.ringSweep(t, 0)
    if (sweep > 0f) {
        val tickCount = 60
        val visible = (sweep * tickCount).toInt()
        val outer = r * 1.24f
        val innerBase = r * 1.15f
        for (index in 0 until visible) {
            val cardinal = index % 15 == 0
            val major = index % 5 == 0
            val length =
                if (cardinal) {
                    outer - innerBase
                } else if (major) {
                    (outer - innerBase) * 0.72f
                } else {
                    (outer - innerBase) * 0.45f
                }
            val angle = (-90f + index * 6f) * (PI.toFloat() / 180f)
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color =
                    (if (cardinal) accent else tickColor)
                        .copy(alpha = (0.55f + 0.45f * sweep) * alpha),
                start = Offset(center.x + cosA * (outer - length), center.y + sinA * (outer - length)),
                end = Offset(center.x + cosA * outer, center.y + sinA * outer),
                strokeWidth = if (cardinal) 2.dp.toPx() else 1.dp.toPx(),
            )
        }
    }

    // Bezel arc closing the graduation ring.
    val bezelSweep = StartupTimeline.ringSweep(t, 1)
    if (bezelSweep > 0f) {
        drawArc(
            color = accent.copy(alpha = 0.28f * bezelSweep * alpha),
            startAngle = -90f,
            sweepAngle = 360f * bezelSweep,
            useCenter = false,
            topLeft = Offset(center.x - r * 1.31f, center.y - r * 1.31f),
            size = Size(r * 2.62f, r * 2.62f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    // Caliper crosshair: four arms extend from the bezel outward during FOCUS.
    if (focus > 0f) {
        val armInner = r * 1.34f
        val armOuter = r * (1.34f + 0.34f * focus)
        for (direction in 0 until 4) {
            val angle = direction * 90f * (PI.toFloat() / 180f)
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color = tickColor.copy(alpha = 0.34f * focus * alpha),
                start = Offset(center.x + cosA * armInner, center.y + sinA * armInner),
                end = Offset(center.x + cosA * armOuter, center.y + sinA * armOuter),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

    // Rim glint travelling across the crown during FOCUS: an arc on the rim, not a light band
    // across the face (a band reads as a rendering artifact on a machined surface).
    if (focus > 0f && focus < 1f) {
        drawArc(
            color = Color.White.copy(alpha = (if (dark) 0.35f else 0.85f) * (1f - focus) * alpha),
            startAngle = -90f + 360f * focus,
            sweepAngle = 34f,
            useCenter = false,
            topLeft = Offset(center.x - r * 0.985f, center.y - r * 0.985f),
            size = Size(r * 1.97f, r * 1.97f),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }

    // Three calibration points flashing on the graduation ring.
    for (glint in 0..2) {
        val pulse = StartupTimeline.glintPulse(t, glint)
        if (pulse <= 0f) continue
        val angle = (-58f + glint * 58f) * (PI.toFloat() / 180f)
        val gr = r * 1.50f
        val gx = center.x + cos(angle) * gr
        val gy = center.y + sin(angle) * gr
        val arm = r * 0.10f * pulse
        val glintColor = if (glint == 1) deep else warm
        drawLine(glintColor.copy(alpha = 0.9f * pulse * alpha), Offset(gx - arm, gy), Offset(gx + arm, gy), 1.5f.dp.toPx())
        drawLine(glintColor.copy(alpha = 0.9f * pulse * alpha), Offset(gx, gy - arm), Offset(gx, gy + arm), 1.5f.dp.toPx())
    }
}
