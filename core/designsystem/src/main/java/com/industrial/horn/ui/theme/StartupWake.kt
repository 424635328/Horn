package com.industrial.horn.ui.theme

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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The six acts of the cold-start film. Projecting the clock onto the act name is part of the
 * contract: a reviewer (or a test) can assert "t = 5.5 s is DRAWING" instead of eyeballing a
 * screenshot, and `StartupStage` is what the timeline tests pin down.
 */
enum class StartupStage {
    PULSE,
    IDENTITY,
    DRAWING,
    COLORIZE,
    PORTRAIT,
    SHUTTER,
}

/**
 * Cold-start film timeline — the six-act storyboard of the startup-animation skill, expressed as
 * pure functions of one elapsed clock. Nothing here schedules, mutates or remembers: every visual
 * state is `f(t)`, so any frame can be reproduced, scrubbed, frozen for a screenshot or replayed.
 *
 * Storyboard (milliseconds), see docs/spec/03 §11.1:
 *
 * ```
 *  0–1150    PULSE     three phase-loop rings + twelve pressure vents, reticle draws in
 *  1000–4200 IDENTITY  instrument dial wakes (graduations, dashed ring, crosshair), boot log,
 *                      wordmark wipe 1700–2400, one haptic at 2400
 *  4200–7200 DRAWING   brand contour fragments fly in and settle left→right (skill arrival rule)
 *  7200–7700 COLORIZE  line art cross-fades into the titanium/amber mark on identical geometry
 *  7700–9600 PORTRAIT  captions, particles, one diagonal light sweep, slow Ken Burns drift
 *  9600–10400 SHUTTER  scene collapses onto the optical axis, one reveal latch at 9600
 * ```
 *
 * The skill's pacing curves are reused verbatim: a single smoothstep primitive, a milestone curve
 * for perceived progress, a heartbeat for breathing and a `%1` phase loop for staggered repetition.
 */
object StartupTimeline {
    const val TOTAL_MS = 10_400f

    // --- act boundaries ---------------------------------------------------------------------
    const val PULSE_END_MS = 1_150f
    const val IDENTITY_START_MS = 1_000f
    const val IDENTITY_END_MS = 4_200f
    const val DRAWING_START_MS = IDENTITY_END_MS
    const val DRAWING_END_MS = 7_200f
    const val COLORIZE_START_MS = DRAWING_END_MS
    const val COLORIZE_END_MS = 7_700f
    const val PORTRAIT_END_MS = 9_600f
    const val SHUTTER_START_MS = PORTRAIT_END_MS
    const val SHUTTER_END_MS = TOTAL_MS

    // --- act-internal beats -----------------------------------------------------------------
    const val IDENTITY_FADE_MS = 650f
    const val GRADUATION_START_MS = 1_200f
    const val GRADUATION_SPAN_MS = 1_400f
    const val DASH_START_MS = 1_300f
    const val CROSSHAIR_START_MS = 1_400f
    const val CROSSHAIR_SPAN_MS = 500f
    const val TITLE_START_MS = 1_700f
    const val TITLE_SPAN_MS = 700f
    const val DRAWING_SPAN_MS = DRAWING_END_MS - DRAWING_START_MS
    const val COLORIZE_SPAN_MS = COLORIZE_END_MS - COLORIZE_START_MS
    const val SPREAD_START_MS = 4_000f
    const val SPREAD_SPAN_MS = 500f
    const val SWEEP_START_MS = 7_900f
    const val SWEEP_SPAN_MS = 900f
    const val SHUTTER_SPAN_MS = SHUTTER_END_MS - SHUTTER_START_MS
    const val BACKGROUND_FADE_MS = 520f
    const val FLASH_START_MS = 9_900f
    const val FLASH_PEAK_MS = 10_050f
    const val FLASH_END_MS = 10_330f
    const val FADE_START_MS = 10_000f

    /** Milliseconds into the film where the fragments begin to arrive (skill: 4200). */
    const val FRAGMENT_FRONT_GAIN = 1.5f
    const val FRAGMENT_FRONT_BIAS = -0.25f

    /** Soft width of the arrival front in normalised subject-x; wider than it looks, 1 pass. */
    const val ARRIVAL_BAND = 0.11f

    // --- host-facing beats ------------------------------------------------------------------

    /** The single reveal latch: fired once, exactly when the shutter starts. */
    const val REVEAL_LATCH_MS = SHUTTER_START_MS

    /** The single light haptic; it lands where the wordmark wipe completes. */
    const val HAPTIC_MS = 2_400f

    /** Hard upper bound for the tap-to-skip collapse. */
    const val SKIP_COLLAPSE_MS = 120f

    /** Reduced motion renders this still frame (colorised mark at rest) instead of the film. */
    const val REDUCED_MOTION_FRAME_MS = 8_800f

    /** How long the reduced-motion still frame stays up before the overlay retires itself. */
    const val REDUCED_MOTION_HOLD_MS = 420f

    // --- HUD beats --------------------------------------------------------------------------
    const val BOOT_LINE_COUNT = 3
    const val BOOT_LINE_START_MS = 1_200f
    const val BOOT_LINE_STEP_MS = 400f
    const val BOOT_LINE_FADE_MS = 140f
    const val CAPTION_COUNT = 2
    const val CAPTION_START_MS = 7_800f
    const val CAPTION_STEP_MS = 600f
    const val CAPTION_FADE_MS = 140f
    const val PARTICLE_COUNT = 6
    const val RING_COUNT = 3
    const val VENT_COUNT = 12

    /** Perceived-progress milestones: [time ratio, progress ratio], slow start, quick middle. */
    private val MILESTONE_TIME = floatArrayOf(0f, 0.12f, 0.35f, 0.56f, 0.66f, 0.72f, 0.77f, 1f)
    private val MILESTONE_PROGRESS = floatArrayOf(0f, 0.06f, 0.41f, 0.76f, 0.924f, 0.97f, 1f, 1f)

    /** Exact act projection — the single place that decides what "5.5 s" means. */
    fun stageOf(t: Float): StartupStage =
        when {
            t < PULSE_END_MS -> StartupStage.PULSE
            t < IDENTITY_END_MS -> StartupStage.IDENTITY
            t < DRAWING_END_MS -> StartupStage.DRAWING
            t < COLORIZE_END_MS -> StartupStage.COLORIZE
            t < PORTRAIT_END_MS -> StartupStage.PORTRAIT
            else -> StartupStage.SHUTTER
        }

    /** Milestone pacing curve shared by the sync bar and the fragment front. */
    fun progress(t: Float): Float {
        val ratio = (t / TOTAL_MS).coerceIn(0f, 1f)
        for (index in 1 until MILESTONE_TIME.size) {
            if (ratio <= MILESTONE_TIME[index]) {
                val from = MILESTONE_TIME[index - 1]
                val to = MILESTONE_TIME[index]
                val span = to - from
                val local = if (span <= 0f) 1f else smooth01((ratio - from) / span)
                return MILESTONE_PROGRESS[index - 1] +
                    (MILESTONE_PROGRESS[index] - MILESTONE_PROGRESS[index - 1]) * local
            }
        }
        return 1f
    }

    // --- PULSE ------------------------------------------------------------------------------

    /** `%1` phase loop: `(t*0.00075 + i*0.33) % 1` — the skill's staggered ring law. */
    fun pulseRingPhase(
        t: Float,
        index: Int,
    ): Float = phaseLoop(t, 0.00075f, index * 0.33f)

    fun pulseRingRadius(
        t: Float,
        index: Int,
        innerRadius: Float,
        outerRadius: Float,
    ): Float {
        val phase = pulseRingPhase(t, index)
        return innerRadius + (outerRadius - innerRadius) * phase
    }

    fun pulseRingAlpha(
        t: Float,
        index: Int,
    ): Float {
        val phase = pulseRingPhase(t, index)
        val fade = 1f - smoothstep(PULSE_END_MS - 320f, PULSE_END_MS, t)
        return (1f - phase) * (1f - phase) * 0.55f * fade
    }

    /** Twelve pressure vents radiate once per 260 ms during the pulse (phase loop again). */
    fun ventPhase(t: Float): Float = phaseLoop(t, 1f / 260f, 0f)

    fun ventEnvelope(t: Float): Float = 1f - smoothstep(PULSE_END_MS - 320f, PULSE_END_MS, t)

    fun reticleProgress(t: Float): Float = smoothstep(0f, 320f, t)

    // --- IDENTITY ---------------------------------------------------------------------------

    /** Dial + wordmark presence: `seg(t, 1000, 650)` as the skill's identity fade. */
    fun identityAlpha(t: Float): Float = smoothstep(IDENTITY_START_MS, IDENTITY_START_MS + IDENTITY_FADE_MS, t)

    /** Dial settles from 0.94 with one underdamped step — the instrument "locking on". */
    fun dialScale(t: Float): Float = 0.94f + 0.06f * settle(seg(t, IDENTITY_START_MS, 900f))

    /** 60 graduations sweep clockwise; cardinal marks carry the accent. */
    fun graduationSweep(t: Float): Float = smoothstep(GRADUATION_START_MS, GRADUATION_START_MS + GRADUATION_SPAN_MS, t)

    fun dashAlpha(t: Float): Float = smoothstep(DASH_START_MS, DASH_START_MS + 600f, t)

    /** Dashed HUD ring turns at 0.010°/ms once awake — scanning, not vibrating. */
    fun dialRotation(t: Float): Float = if (t <= DASH_START_MS) 0f else (t - DASH_START_MS) * 0.010f

    fun crosshairProgress(t: Float): Float = smoothstep(CROSSHAIR_START_MS, CROSSHAIR_START_MS + CROSSHAIR_SPAN_MS, t)

    /** Calibration-point flashes, one window per point. */
    fun glintPulse(
        t: Float,
        index: Int,
    ): Float {
        val start = 2_300f + index * 220f
        return smoothstep(start, start + 60f, t) * (1f - smoothstep(start + 120f, start + 260f, t))
    }

    /** One boot-log line fades in at 1200 / 1600 / 2000 ms (layer alpha only, never re-lays out). */
    fun bootLineAlpha(
        t: Float,
        index: Int,
    ): Float {
        val start = BOOT_LINE_START_MS + index * BOOT_LINE_STEP_MS
        return smoothstep(start, start + BOOT_LINE_FADE_MS, t)
    }

    /** Wordmark wipe: clip reveal 1700 → 2400, the skill's title rule. */
    fun titleReveal(t: Float): Float = smoothstep(TITLE_START_MS, TITLE_START_MS + TITLE_SPAN_MS, t)

    /** Identity tiles scatter just before the contour takes over (identity → drawing handoff). */
    fun identitySpread(t: Float): Float = smoothstep(SPREAD_START_MS, SPREAD_START_MS + SPREAD_SPAN_MS, t)

    // --- DRAWING ----------------------------------------------------------------------------

    /** Linear assembly progress `p = clamp((t - 4200) / 3000)` — deliberately un-eased. */
    fun drawingProgress(t: Float): Float = ((t - DRAWING_START_MS) / DRAWING_SPAN_MS).coerceIn(0f, 1f)

    /** Arrival front in normalised subject x: left edge lands first, bell and right edge last. */
    fun contourFront(t: Float): Float = drawingProgress(t) * FRAGMENT_FRONT_GAIN + FRAGMENT_FRONT_BIAS

    /**
     * Per-fragment arrival `smooth((front - x/W + 0.16) / 0.32)` with the band narrowed to
     * [ARRIVAL_BAND] so only ~2 of 24 columns are in flight at once (frame budget, not taste).
     */
    fun fragmentArrival(
        t: Float,
        normalisedX: Float,
    ): Float = smooth01((contourFront(t) - normalisedX + ARRIVAL_BAND * 0.5f) / ARRIVAL_BAND)

    // --- COLORIZE ---------------------------------------------------------------------------

    /** Line art ↔ colorised mark cross-fade on identical geometry. */
    fun colorizeMix(t: Float): Float = smoothstep(COLORIZE_START_MS, COLORIZE_START_MS + COLORIZE_SPAN_MS, t)

    // --- PORTRAIT ---------------------------------------------------------------------------
    fun captionAlpha(
        t: Float,
        index: Int,
    ): Float {
        val start = CAPTION_START_MS + index * CAPTION_STEP_MS
        return smoothstep(start, start + CAPTION_FADE_MS, t)
    }

    /** One diagonal light sweep crosses the frame during the portrait act. */
    fun lightSweep(t: Float): Float = smoothstep(SWEEP_START_MS, SWEEP_START_MS + SWEEP_SPAN_MS, t)

    /** Ken Burns drift: +1.2% at the colorize handoff, another +0.8% through the portrait. */
    fun kenBurnsScale(t: Float): Float =
        1f +
            0.012f * smoothstep(COLORIZE_START_MS, COLORIZE_START_MS + COLORIZE_SPAN_MS, t) +
            0.008f * smoothstep(COLORIZE_END_MS, COLORIZE_END_MS + 1_900f, t)

    /** Breathing particles: heartbeat wave, staggered by 170 ms, gone before the shutter. */
    fun particleAlpha(
        t: Float,
        index: Int,
    ): Float {
        val breath = heartbeat(t - COLORIZE_END_MS - index * 170f)
        val arrival = smoothstep(COLORIZE_END_MS, COLORIZE_END_MS + 420f, t)
        val exit = 1f - smoothstep(PORTRAIT_END_MS - 250f, PORTRAIT_END_MS, t)
        return breath * 0.55f * arrival * exit
    }

    fun particleRise(t: Float): Float = smoothstep(COLORIZE_END_MS, COLORIZE_END_MS + 1_900f, t)

    // --- SHUTTER ----------------------------------------------------------------------------

    /** Whole scene squeezes onto the optical axis: `scaleY(1 - collapse)`. */
    fun shutterCollapse(t: Float): Float = smoothstep(SHUTTER_START_MS, SHUTTER_END_MS, t)

    /** The seam beam pinches shut: `scaleX(1 - pinch)`. */
    fun centerLinePinch(t: Float): Float = smoothstep(SHUTTER_START_MS, SHUTTER_END_MS, t)

    /** One-shot flash as the two halves meet. */
    fun flashAlpha(t: Float): Float = smoothstep(FLASH_START_MS, FLASH_PEAK_MS, t) * (1f - smoothstep(FLASH_PEAK_MS, FLASH_END_MS, t))

    /** The boot surface dissolves during the shutter so the real UI shows between the halves. */
    fun backgroundAlpha(t: Float): Float = 1f - smoothstep(SHUTTER_START_MS, SHUTTER_START_MS + BACKGROUND_FADE_MS, t)

    /** Residual overlay alpha; reaches 0 exactly at TOTAL_MS, when the layer leaves composition. */
    fun overlayAlpha(t: Float): Float = 1f - smoothstep(FADE_START_MS, TOTAL_MS, t)

    /** Host-interface reveal progress (staggered islands), 0 before the latch, 1 at the end. */
    fun revealProgress(t: Float): Float = smoothstep(SHUTTER_START_MS, SHUTTER_END_MS, t)

    /** Sync bar consumption of the milestone curve. */
    fun syncProgress(t: Float): Float = progress(t)
}

/** One smoothstep segment `seg(t, start, span)` — the only easing primitive in the film. */
fun seg(
    t: Float,
    start: Float,
    span: Float,
): Float = smoothstep(start, start + span, t)

/** Classic smoothstep — C¹ continuous, the single easing primitive of the whole film. */
fun smoothstep(
    edge0: Float,
    edge1: Float,
    x: Float,
): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val ratio = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return ratio * ratio * (3f - 2f * ratio)
}

/** smoothstep on a raw 0..1 argument (skill's `smooth()`), used by the arrival rule. */
fun smooth01(value: Float): Float {
    val ratio = value.coerceIn(0f, 1f)
    return ratio * ratio * (3f - 2f * ratio)
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

/** Heartbeat: `((1 - cos(2πt/T)) / 2)²` — breathing reads as organic, a sine does not. */
fun heartbeat(
    t: Float,
    period: Float = 1_080f,
): Float {
    val safe = if (t <= 0f) 0f else t
    val wave = (1f - cos(safe * 2f * PI.toFloat() / period)) * 0.5f
    return wave * wave
}

/** `%1` phase loop: staggered repetition for rings, vents and particles. */
fun phaseLoop(
    t: Float,
    speed: Float,
    offset: Float = 0f,
): Float = (((t * speed + offset) % 1f) + 1f) % 1f

/**
 * Staggered reveal progress for main-UI islands during the shutter handoff: each island rises
 * with a spring settle, offset from the previous one by [STAGGER_STEP] of the reveal window.
 * Returns 1 for a fully entered island, so callers can default the provider to "already revealed"
 * and stay pixel-identical outside startup.
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

internal const val TICK_COUNT = 60
internal const val DASH_COUNT = 48

/**
 * Precomputed gradient/geometry work for one wake scene. Every path, gradient and trigonometry
 * table is built once per size/colour change, so the per-frame draw allocates nothing.
 */
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
    val ventCos: FloatArray,
    val ventSin: FloatArray,
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
            val ventCos = FloatArray(StartupTimeline.VENT_COUNT)
            val ventSin = FloatArray(StartupTimeline.VENT_COUNT)
            for (index in 0 until StartupTimeline.VENT_COUNT) {
                val angle = (index * 30f + 15f) * (PI.toFloat() / 180f)
                ventCos[index] = cos(angle)
                ventSin[index] = sin(angle)
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
                        center = center,
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
                                Color.White.copy(alpha = if (dark) 0.035f else 0.03f),
                                Color.Transparent,
                            ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.22f, size.height * 0.06f),
                    ),
                tickCos = tickCos,
                tickSin = tickSin,
                dashCos = dashCos,
                dashSin = dashSin,
                ventCos = ventCos,
                ventSin = ventSin,
            )
        }
    }
}

/**
 * Atmosphere: the boot surface, its wash, the scanline bed and the four-corner reticle.
 * Drawn first in every frame; the shutter fades it out by lowering [alpha].
 */
fun DrawScope.drawStartupAtmosphere(
    t: Float,
    brushes: WakeSceneBrushes,
    palette: HornColors,
    dark: Boolean,
    surface: Color,
    alpha: Float,
    backgroundAlpha: Float,
) {
    if (backgroundAlpha > 0f) {
        drawRect(color = surface, alpha = backgroundAlpha)
    }
    if (alpha <= 0f) return
    drawRect(brush = brushes.wash, alpha = alpha)
    drawPath(
        path = brushes.scanlines,
        color = palette.textTertiary.copy(alpha = if (dark) 0.05f else 0.04f),
        style = Stroke(1f),
        alpha = alpha,
    )
    drawCornerReticle(
        progress = StartupTimeline.reticleProgress(t),
        palette = palette,
        accent = palette.defaultAccent,
        alpha = alpha,
    )
}

/** Four corner brackets: the classic boot-sequence reticle, with one live accent tick. */
private fun DrawScope.drawCornerReticle(
    progress: Float,
    palette: HornColors,
    accent: Color,
    alpha: Float,
) {
    if (progress <= 0f) return
    val inset = 26.dp.toPx()
    val arm = 26.dp.toPx() * progress
    val stroke = 1.2.dp.toPx()
    val color = palette.hairlineStrong.copy(alpha = 0.85f * alpha)
    drawBracket(inset, inset, 1f, 1f, arm, color, stroke)
    drawBracket(size.width - inset, inset, -1f, 1f, arm, color, stroke)
    drawBracket(inset, size.height - inset, 1f, -1f, arm, color, stroke)
    drawBracket(size.width - inset, size.height - inset, -1f, -1f, arm, color, stroke)
    if (arm > 6.dp.toPx()) {
        // A single accent tick marks the reticle as a live instrument, not decoration.
        drawLine(
            color = accent.copy(alpha = 0.8f * alpha),
            start = Offset(inset, inset + arm + 6.dp.toPx()),
            end = Offset(inset, inset + arm + 14.dp.toPx()),
            strokeWidth = 1.2.dp.toPx(),
        )
    }
}

private fun DrawScope.drawBracket(
    x: Float,
    y: Float,
    dx: Float,
    dy: Float,
    arm: Float,
    color: Color,
    stroke: Float,
) {
    drawLine(color, Offset(x, y), Offset(x + arm * dx, y), stroke)
    drawLine(color, Offset(x, y), Offset(x, y + arm * dy), stroke)
}

/**
 * PULSE act: three phase-loop rings expand from the optical centre while twelve vents radiate.
 * The rings are the skill's `(t*0.00075 + i*0.33) % 1` law; they fade out as IDENTITY takes over.
 */
fun DrawScope.drawPulseAct(
    t: Float,
    brushes: WakeSceneBrushes,
    center: Offset,
    radius: Float,
    accent: Color,
    palette: HornColors,
    dark: Boolean,
    alpha: Float,
) {
    if (t >= StartupTimeline.PULSE_END_MS || alpha <= 0f) return
    val ventEnvelope = StartupTimeline.ventEnvelope(t)
    if (ventEnvelope > 0f) {
        val phase = StartupTimeline.ventPhase(t)
        val innerBase = radius * 0.30f
        for (vent in 0 until StartupTimeline.VENT_COUNT) {
            val progress = (phase + vent / StartupTimeline.VENT_COUNT.toFloat()) % 1f
            val inner = radius * 0.62f + innerBase * progress
            val outer = inner + radius * 0.30f * progress
            drawLine(
                color = accent.copy(alpha = (1f - progress) * 0.40f * ventEnvelope * alpha),
                start = Offset(center.x + brushes.ventCos[vent] * inner, center.y + brushes.ventSin[vent] * inner),
                end = Offset(center.x + brushes.ventCos[vent] * outer, center.y + brushes.ventSin[vent] * outer),
                strokeWidth = 1.3.dp.toPx(),
            )
        }
    }
    for (ring in 0 until StartupTimeline.RING_COUNT) {
        val ringAlpha = StartupTimeline.pulseRingAlpha(t, ring)
        if (ringAlpha <= 0f) continue
        val ringRadius =
            StartupTimeline.pulseRingRadius(
                t = t,
                index = ring,
                innerRadius = radius * 0.46f,
                outerRadius = radius * 1.16f,
            )
        drawCircle(
            color = (if (dark) accent else palette.textTertiary).copy(alpha = ringAlpha * alpha),
            radius = ringRadius,
            center = center,
            style = Stroke(width = if (ring == 0) 1.6.dp.toPx() else 1.dp.toPx()),
        )
    }
    // The instrument silhouette itself wakes underneath the rings.
    val wake = StartupTimeline.identityAlpha(t) * 0.55f
    if (wake > 0f) {
        drawCircle(
            brush = brushes.dialBody,
            radius = radius * 0.30f,
            center = center,
            alpha = wake * alpha,
        )
    }
}

/**
 * IDENTITY act: the instrument dial wakes up — graduations sweep in, the dashed HUD ring starts
 * turning, the caliper crosshair extends, calibration points flash and the emblem sits inside.
 * [emblem] draws the brand contour at [emblemScale] of its final size, binding acts 2 and 3.
 */
fun DrawScope.drawIdentityAct(
    t: Float,
    brushes: WakeSceneBrushes,
    palette: HornColors,
    accent: Color,
    dark: Boolean,
    center: Offset,
    radius: Float,
    alpha: Float,
) {
    val identity = StartupTimeline.identityAlpha(t) * alpha
    if (identity <= 0f) return
    val spread = StartupTimeline.identitySpread(t)
    // The dial must be gone by DRAWING: a ghost instrument behind the assembling mark reads as
    // a rendering bug, not as a transition.
    val dialAlpha = identity * (1f - spread)
    val scale = StartupTimeline.dialScale(t)
    val r = radius * scale

    drawCircle(brush = brushes.dialGlow, radius = r * 1.30f, center = center, alpha = dialAlpha)
    drawCircle(brush = brushes.dialBody, radius = r, center = center, alpha = dialAlpha)
    for (ring in 1..3) {
        drawCircle(
            color = palette.textTertiary.copy(alpha = 0.20f * dialAlpha),
            radius = r * (0.36f + ring * 0.17f),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    for (cut in 0 until DASH_COUNT) {
        drawLine(
            color = palette.textTertiary.copy(alpha = 0.38f * dialAlpha),
            start = Offset(center.x + brushes.dashCos[cut] * r * 0.93f, center.y + brushes.dashSin[cut] * r * 0.93f),
            end = Offset(center.x + brushes.dashCos[cut] * r * 0.99f, center.y + brushes.dashSin[cut] * r * 0.99f),
            strokeWidth = 1.dp.toPx(),
        )
    }
    drawCircle(
        color = (if (dark) accent else palette.textTertiary).copy(alpha = (if (dark) 0.55f else 0.85f) * dialAlpha),
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
        alpha = dialAlpha,
    )

    // Closed bezel + dashed HUD ring turning slowly.
    drawCircle(
        color = palette.hairlineStrong.copy(alpha = 0.55f * dialAlpha),
        radius = r * 1.30f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    val dashRing = StartupTimeline.dashAlpha(t) * dialAlpha
    if (dashRing > 0f) {
        val dashRadius = r * 1.40f
        rotate(degrees = StartupTimeline.dialRotation(t), pivot = center) {
            for (index in 0 until DASH_COUNT) {
                drawLine(
                    color = accent.copy(alpha = 0.22f * dashRing),
                    start = Offset(center.x + brushes.dashCos[index] * dashRadius, center.y + brushes.dashSin[index] * dashRadius),
                    end =
                        Offset(
                            center.x + brushes.dashCos[index] * (dashRadius + 4.dp.toPx()),
                            center.y + brushes.dashSin[index] * (dashRadius + 4.dp.toPx()),
                        ),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }

    // 60 graduations sweep in clockwise; every 5th is long, every 15th carries the accent.
    val sweep = StartupTimeline.graduationSweep(t)
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
                        .copy(alpha = (0.55f + 0.45f * sweep) * dialAlpha),
                start =
                    Offset(
                        center.x + brushes.tickCos[index] * (outer - length),
                        center.y + brushes.tickSin[index] * (outer - length),
                    ),
                end = Offset(center.x + brushes.tickCos[index] * outer, center.y + brushes.tickSin[index] * outer),
                strokeWidth = if (cardinal) 2.dp.toPx() else 1.dp.toPx(),
            )
        }
    }

    // Caliper crosshair.
    val crosshair = StartupTimeline.crosshairProgress(t)
    if (crosshair > 0f) {
        val armInner = r * 1.58f
        val armOuter = r * (1.58f + 0.20f * crosshair)
        for (direction in 0 until 4) {
            val angle = direction * 90f * (PI.toFloat() / 180f)
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color = palette.textTertiary.copy(alpha = 0.34f * crosshair * dialAlpha),
                start = Offset(center.x + cosA * armInner, center.y + sinA * armInner),
                end = Offset(center.x + cosA * armOuter, center.y + sinA * armOuter),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

    // Rim glint travelling across the crown as the dial locks on.
    val lock = StartupTimeline.graduationSweep(t)
    if (lock > 0f && lock < 1f) {
        drawArc(
            color = Color.White.copy(alpha = (if (dark) 0.35f else 0.85f) * (1f - lock) * dialAlpha),
            startAngle = -90f + 360f * lock,
            sweepAngle = 34f,
            useCenter = false,
            topLeft = Offset(center.x - r * 0.985f, center.y - r * 0.985f),
            size = Size(r * 1.97f, r * 1.97f),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }

    // Calibration glints.
    for (glint in 0..2) {
        val pulse = StartupTimeline.glintPulse(t, glint)
        if (pulse <= 0f) continue
        val angle = (-58f + glint * 58f) * (PI.toFloat() / 180f)
        val glintRadius = r * 1.50f
        val gx = center.x + cos(angle) * glintRadius
        val gy = center.y + sin(angle) * glintRadius
        val arm = r * 0.10f * pulse
        val glintColor = if (glint == 1) palette.textTertiary else accent
        drawLine(glintColor.copy(alpha = 0.9f * pulse * dialAlpha), Offset(gx - arm, gy), Offset(gx + arm, gy), 1.5.dp.toPx())
        drawLine(glintColor.copy(alpha = 0.9f * pulse * dialAlpha), Offset(gx, gy - arm), Offset(gx, gy + arm), 1.5.dp.toPx())
    }
}

/**
 * Identity tiles: the dial's dashed ring breaks into 24 short arcs that fly outward and fade as
 * the contour fragments take over — the skill's "身份块散开 → 轮廓碎片接棒" handoff.
 */
fun DrawScope.drawIdentitySpread(
    t: Float,
    center: Offset,
    radius: Float,
    accent: Color,
    alpha: Float,
) {
    val spread = StartupTimeline.identitySpread(t)
    if (spread <= 0f || spread >= 1f) return
    val fade = (1f - spread) * alpha
    if (fade <= 0f) return
    val tiles = 24
    val baseRadius = radius * 1.18f
    val angled = spread * 40.dp.toPx()
    for (tile in 0 until tiles) {
        val angle = (tile * (360f / tiles)) * (PI.toFloat() / 180f)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val outer = baseRadius + angled * (0.55f + 0.45f * ((tile % 3) / 2f))
        val inner = outer - radius * 0.10f
        drawLine(
            color = accent.copy(alpha = 0.42f * fade),
            start = Offset(center.x + cosA * inner, center.y + sinA * inner),
            end = Offset(center.x + cosA * outer, center.y + sinA * outer),
            strokeWidth = 1.4.dp.toPx(),
        )
    }
}

/** One diagonal light sweep, used once during PORTRAIT (skill: 光扫一次性). */
fun DrawScope.drawLightSweep(
    t: Float,
    brushes: WakeSceneBrushes,
    alpha: Float,
) {
    val sweep = StartupTimeline.lightSweep(t)
    if (sweep <= 0f || sweep >= 1f || alpha <= 0f) return
    translate(left = (-0.45f + 1.9f * sweep) * size.width) {
        drawRect(brush = brushes.sweep, alpha = (1f - abs(0.5f - sweep) * 1.2f) * alpha)
    }
}

/** Portrait particles: ≤6 points, breathing on a heartbeat, drifting up slowly. */
fun DrawScope.drawParticles(
    t: Float,
    accent: Color,
    color: Color,
    radiusPx: Float,
    risePx: Float,
    alpha: Float,
    reduced: Boolean,
) {
    if (reduced || t < StartupTimeline.COLORIZE_END_MS || alpha <= 0f) return
    val rise = StartupTimeline.particleRise(t) * risePx
    for (index in 0 until StartupTimeline.PARTICLE_COUNT) {
        val particleAlpha = StartupTimeline.particleAlpha(t, index)
        if (particleAlpha <= 0f) continue
        val fx = PARTICLE_X[index]
        val fy = PARTICLE_Y[index]
        val jitter = sin(t * 0.0009f + index * 1.7f) * 6.dp.toPx()
        drawCircle(
            color = (if (index % 2 == 0) accent else color).copy(alpha = particleAlpha * alpha),
            radius = radiusPx * (0.7f + 0.3f * ((index % 3) / 2f)),
            center = Offset(size.width * fx + jitter, size.height * fy - rise),
        )
    }
}

private val PARTICLE_X = floatArrayOf(0.18f, 0.31f, 0.72f, 0.86f, 0.24f, 0.66f)
private val PARTICLE_Y = floatArrayOf(0.30f, 0.58f, 0.36f, 0.62f, 0.72f, 0.22f)
