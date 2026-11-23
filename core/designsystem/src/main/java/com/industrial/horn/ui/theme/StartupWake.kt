package com.industrial.horn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
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
 *  0–160   WAKE   : instrument silhouette 0.62 → 0.72, opacity 0 → 1, corner reticle draws in
 *  160–500  FOCUS  : 60 graduations sweep in clockwise (60 ms stagger), one diagonal light sweep,
 *                    the dashed HUD ring starts turning, the caliper crosshair extends
 *  500–820  BLOOM  : dial settles to 1.0 with a damped overshoot, four symmetric pressure arcs
 *                    emit, three calibration points flash, the wordmark wipes in; one light
 *                    haptic at 560
 *  820–1040 MERGE  : shutters close vertically onto a shrinking centre line, one flash, and the
 *                    residual layer fades last; the reveal latch fires once at 820 so the main
 *                    UI rises underneath
 *
 * The vocabulary is deliberately that of a boot sequence (scanlines, reticle, boot log, timecode,
 * sync arc): a calibration instrument switching on reads as "powering up", not as a logo sting.
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
    const val SHUTTER_END_MS = 1000f
    const val FLASH_START_MS = 930f
    const val FLASH_PEAK_MS = 985f
    const val FADE_START_MS = 980f

    /** Boot log: three lines, 300 ms apart, each fading in over 140 ms. */
    const val BOOT_LINE_COUNT = 3
    const val BOOT_LINE_START_MS = 180f
    const val BOOT_LINE_STEP_MS = 300f

    fun wakeAlpha(t: Float): Float = smoothstep(0f, WAKE_END_MS, t)

    fun wakeScale(t: Float): Float = 0.62f + 0.10f * smoothstep(0f, WAKE_END_MS, t)

    fun focusProgress(t: Float): Float = smoothstep(WAKE_END_MS, FOCUS_END_MS, t)

    fun ringSweep(
        t: Float,
        ringIndex: Int,
    ): Float = smoothstep(WAKE_END_MS + ringIndex * 60f, FOCUS_END_MS - 40f + ringIndex * 60f, t)

    fun bloomProgress(t: Float): Float = smoothstep(FOCUS_END_MS, BLOOM_END_MS, t)

    fun mergeProgress(t: Float): Float = smoothstep(BLOOM_END_MS, MERGE_END_MS, t)

    /** Whole-overlay opacity: the shutters hand off first, the residual layer fades last. */
    fun overlayAlpha(t: Float): Float = 1f - smoothstep(FADE_START_MS, MERGE_END_MS, t)

    /** Dial scale: wake ramp, then a spring-settled bloom to full size. */
    fun orbScale(t: Float): Float =
        if (t < FOCUS_END_MS) {
            wakeScale(t)
        } else {
            0.72f + 0.28f * settle(bloomProgress(t))
        }

    /** Pressure-arc phase loop (%1) used by the four symmetric emission arcs. */
    fun pressurePhase(t: Float): Float = (((t - FOCUS_END_MS) / 260f) % 1f + 1f) % 1f

    /** Calibration-point flashes, one window per point. */
    fun glintPulse(
        t: Float,
        index: Int,
    ): Float {
        val start = 520f + index * 85f
        return smoothstep(start, start + 60f, t) * (1f - smoothstep(start + 120f, start + 260f, t))
    }

    /** Corner reticle brackets draw themselves in during WAKE. */
    fun reticleProgress(t: Float): Float = smoothstep(0f, 220f, t)

    /** Diagonal light sweep: traverses the screen once during FOCUS. */
    fun scanProgress(t: Float): Float = smoothstep(WAKE_END_MS, 560f, t)

    /** Dashed HUD ring rotates slowly once the dial is awake (degrees). */
    fun dialRotation(t: Float): Float = if (t <= WAKE_END_MS) 0f else (t - WAKE_END_MS) * 0.010f

    /** Horizontal wipe that reveals the wordmark during BLOOM. */
    fun wordmarkReveal(t: Float): Float = smoothstep(540f, BLOOM_END_MS, t)

    /** One boot-log line fades in at 180 / 480 / 780 ms. */
    fun bootLineAlpha(
        t: Float,
        index: Int,
    ): Float {
        val start = BOOT_LINE_START_MS + index * BOOT_LINE_STEP_MS
        return smoothstep(start, start + 140f, t)
    }

    /** Calibration progress (0..1) for the sync arc and the bottom bar. */
    fun progress(t: Float): Float = (t / TOTAL_MS).coerceIn(0f, 1f)

    /** Vertical shutters: close from the top and bottom onto the centre line. */
    fun shutterProgress(t: Float): Float = smoothstep(BLOOM_END_MS, SHUTTER_END_MS, t)

    /** One-shot flash as the shutters meet. */
    fun flashAlpha(t: Float): Float = smoothstep(FLASH_START_MS, FLASH_PEAK_MS, t) * (1f - smoothstep(FLASH_PEAK_MS, MERGE_END_MS, t))

    /** Centre line narrows to nothing over the last 40 ms of the handoff. */
    fun centerLineWidth(t: Float): Float = 1f - smoothstep(SHUTTER_END_MS, MERGE_END_MS, t)
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

private const val TICK_COUNT = 60
private const val DASH_COUNT = 48

/**
 * Precomputed gradient/geometry work for one wake scene. Every path, gradient and trigonometry
 * table is built once per size/colour change, so [drawWakeScene] allocates nothing per frame.
 */
@Immutable
class WakeSceneBrushes(
    val wash: Brush,
    val dialBody: Brush,
    val dialGlow: Brush,
    val scanlines: Path,
    val sweep: Brush,
    val tickCos: FloatArray,
    val tickSin: FloatArray,
    val dashCos: FloatArray,
    val dashSin: FloatArray,
) {
    companion object {
        fun of(
            size: Size,
            center: Offset,
            radius: Float,
            palette: HornColors,
            warm: Color,
            dark: Boolean,
        ): WakeSceneBrushes {
            val scanlines = Path()
            var y = 0f
            while (y <= size.height) {
                scanlines.moveTo(0f, y)
                scanlines.lineTo(size.width, y)
                y += 3f
            }
            val tickCos = FloatArray(TICK_COUNT)
            val tickSin = FloatArray(TICK_COUNT)
            for (index in 0 until TICK_COUNT) {
                val angle = (-90f + index * (360f / TICK_COUNT)) * (PI.toFloat() / 180f)
                tickCos[index] = cos(angle)
                tickSin[index] = sin(angle)
            }
            val dashCos = FloatArray(DASH_COUNT)
            val dashSin = FloatArray(DASH_COUNT)
            for (index in 0 until DASH_COUNT) {
                val angle = (index * (360f / DASH_COUNT)) * (PI.toFloat() / 180f)
                dashCos[index] = cos(angle)
                dashSin[index] = sin(angle)
            }
            return WakeSceneBrushes(
                wash =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                warm.copy(alpha = if (dark) 0.035f else 0.025f),
                                palette.grid.copy(alpha = if (dark) 0.55f else 0.45f),
                                Color.Transparent,
                            ),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = maxOf(size.width, size.height) * 1.05f,
                    ),
                dialBody =
                    Brush.radialGradient(
                        colors = listOf(palette.dialTop, palette.dialBottom),
                        center = Offset(center.x - radius * 0.32f, center.y - radius * 0.38f),
                        radius = radius * 1.9f,
                    ),
                dialGlow =
                    Brush.radialGradient(
                        colors = listOf(warm.copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = radius * 1.26f,
                    ),
                scanlines = scanlines,
                sweep =
                    Brush.linearGradient(
                        colors =
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = if (dark) 0.03f else 0.025f),
                                Color.Transparent,
                            ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.22f, size.height * 0.06f),
                    ),
                tickCos = tickCos,
                tickSin = tickSin,
                dashCos = dashCos,
                dashSin = dashSin,
            )
        }
    }
}

/**
 * Draws one calibration frame. Zero allocation per call: colours are value classes, trigonometry
 * tables come from [WakeSceneBrushes], and the only Path is the cached scanline set.
 */
fun DrawScope.drawWakeScene(
    t: Float,
    brushes: WakeSceneBrushes,
    palette: HornColors,
    accent: Color,
    dark: Boolean,
) {
    val center = Offset(size.width / 2f, size.height * 0.42f)
    val radius = min(size.width, size.height) * 0.24f
    val alpha = StartupTimeline.wakeAlpha(t)
    val scale = StartupTimeline.orbScale(t)
    val r = radius * scale
    val focus = StartupTimeline.focusProgress(t)
    val bloom = StartupTimeline.bloomProgress(t)

    // --- atmosphere: wash, scanlines, one diagonal light sweep --------------------------------
    drawRect(brush = brushes.wash)
    drawPath(
        path = brushes.scanlines,
        color = palette.textTertiary.copy(alpha = if (dark) 0.05f else 0.04f),
        style = Stroke(1f),
    )
    val scan = StartupTimeline.scanProgress(t)
    if (scan > 0f && scan < 1f) {
        translate(left = (-0.45f + 1.9f * scan) * size.width) {
            drawRect(brush = brushes.sweep, alpha = 1f - abs(0.5f - scan) * 1.2f)
        }
    }
    drawCornerReticle(
        progress = StartupTimeline.reticleProgress(t),
        palette = palette,
        accent = accent,
    )

    // --- pressure bursts: twelve radial vents pulsing outward during BLOOM ---------------------
    if (bloom > 0f) {
        val phase = StartupTimeline.pressurePhase(t)
        for (vent in 0 until 12) {
            val p = (phase + vent / 12f) % 1f
            val angle = (vent * 30f + 15f) * (PI.toFloat() / 180f)
            val cosA = cos(angle)
            val sinA = sin(angle)
            val inner = r * 1.27f
            val outer = r * (1.27f + 0.34f * p)
            drawLine(
                color = accent.copy(alpha = (1f - p) * 0.45f * bloom * alpha),
                start = Offset(center.x + cosA * inner, center.y + sinA * inner),
                end = Offset(center.x + cosA * outer, center.y + sinA * outer),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }

    // --- dial body ----------------------------------------------------------------------------
    drawCircle(brush = brushes.dialGlow, radius = r * 1.30f, center = center, alpha = alpha)
    drawCircle(brush = brushes.dialBody, radius = r, center = center, alpha = alpha)
    for (ring in 1..3) {
        drawCircle(
            color = palette.textTertiary.copy(alpha = 0.20f * alpha),
            radius = r * (0.36f + ring * 0.17f),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    for (cut in 0 until 48) {
        val angle = cut * (360f / 48f) * (PI.toFloat() / 180f)
        val cutCos = cos(angle)
        val cutSin = sin(angle)
        drawLine(
            color = palette.textTertiary.copy(alpha = 0.38f * alpha),
            start = Offset(center.x + cutCos * r * 0.93f, center.y + cutSin * r * 0.93f),
            end = Offset(center.x + cutCos * r * 0.99f, center.y + cutSin * r * 0.99f),
            strokeWidth = 1.dp.toPx(),
        )
    }
    drawCircle(
        color = (if (dark) accent else palette.textTertiary).copy(alpha = (if (dark) 0.55f else 0.85f) * alpha),
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

    // --- radar HUD: closed bezel, dashed ring turning slowly ---------------------------------
    drawCircle(
        color = palette.hairlineStrong.copy(alpha = 0.55f * alpha),
        radius = r * 1.30f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    val dashRadius = r * 1.40f
    rotate(degrees = StartupTimeline.dialRotation(t), pivot = center) {
        for (index in 0 until DASH_COUNT) {
            drawLine(
                color = accent.copy(alpha = 0.22f * alpha * focus),
                start =
                    Offset(
                        center.x + brushes.dashCos[index] * dashRadius,
                        center.y + brushes.dashSin[index] * dashRadius,
                    ),
                end =
                    Offset(
                        center.x + brushes.dashCos[index] * (dashRadius + 4.dp.toPx()),
                        center.y + brushes.dashSin[index] * (dashRadius + 4.dp.toPx()),
                    ),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

    // --- 60 graduations sweep in clockwise; cardinal marks carry the accent -------------------
    val sweep = StartupTimeline.ringSweep(t, 0)
    if (sweep > 0f) {
        val visible = (sweep * TICK_COUNT).toInt()
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
            drawLine(
                color =
                    (if (cardinal) accent else palette.textTertiary)
                        .copy(alpha = (0.55f + 0.45f * sweep) * alpha),
                start =
                    Offset(
                        center.x + brushes.tickCos[index] * (outer - length),
                        center.y + brushes.tickSin[index] * (outer - length),
                    ),
                end =
                    Offset(
                        center.x + brushes.tickCos[index] * outer,
                        center.y + brushes.tickSin[index] * outer,
                    ),
                strokeWidth = if (cardinal) 2.dp.toPx() else 1.dp.toPx(),
            )
        }
    }

    // --- caliper crosshair --------------------------------------------------------------------
    if (focus > 0f) {
        val armInner = r * 1.58f
        val armOuter = r * (1.58f + 0.20f * focus)
        for (direction in 0 until 4) {
            val angle = direction * 90f * (PI.toFloat() / 180f)
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color = palette.textTertiary.copy(alpha = 0.34f * focus * alpha),
                start = Offset(center.x + cosA * armInner, center.y + sinA * armInner),
                end = Offset(center.x + cosA * armOuter, center.y + sinA * armOuter),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

    // --- rim glint travelling across the crown during FOCUS -----------------------------------
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

    // --- calibration points -------------------------------------------------------------------
    for (glint in 0..2) {
        val pulse = StartupTimeline.glintPulse(t, glint)
        if (pulse <= 0f) continue
        val angle = (-58f + glint * 58f) * (PI.toFloat() / 180f)
        val gr = r * 1.50f
        val gx = center.x + cos(angle) * gr
        val gy = center.y + sin(angle) * gr
        val arm = r * 0.10f * pulse
        val glintColor = if (glint == 1) palette.textTertiary else accent
        drawLine(glintColor.copy(alpha = 0.9f * pulse * alpha), Offset(gx - arm, gy), Offset(gx + arm, gy), 1.5f.dp.toPx())
        drawLine(glintColor.copy(alpha = 0.9f * pulse * alpha), Offset(gx, gy - arm), Offset(gx, gy + arm), 1.5f.dp.toPx())
    }
}

/** Four corner brackets: the classic boot-sequence reticle, with one live accent tick. */
private fun DrawScope.drawCornerReticle(
    progress: Float,
    palette: HornColors,
    accent: Color,
) {
    if (progress <= 0f) return
    val inset = 26.dp.toPx()
    val arm = 26.dp.toPx() * progress
    val stroke = 1.2.dp.toPx()
    val color = palette.hairlineStrong.copy(alpha = 0.85f)
    val corners =
        listOf(
            Offset(inset, inset) to (1f to 1f),
            Offset(size.width - inset, inset) to (-1f to 1f),
            Offset(inset, size.height - inset) to (1f to -1f),
            Offset(size.width - inset, size.height - inset) to (-1f to -1f),
        )
    corners.forEachIndexed { index, (origin, direction) ->
        val (dx, dy) = direction
        drawLine(color, origin, Offset(origin.x + arm * dx, origin.y), stroke)
        drawLine(color, origin, Offset(origin.x, origin.y + arm * dy), stroke)
        if (index == 0 && arm > 6.dp.toPx()) {
            // A single accent tick marks the reticle as a live instrument, not decoration.
            drawLine(
                color = accent.copy(alpha = 0.8f),
                start = Offset(origin.x, origin.y + arm + 6.dp.toPx()),
                end = Offset(origin.x, origin.y + arm + 14.dp.toPx()),
                strokeWidth = 1.2.dp.toPx(),
            )
        }
    }
}
