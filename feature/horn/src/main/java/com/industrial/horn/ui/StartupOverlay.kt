package com.industrial.horn.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.feature.R
import com.industrial.horn.ui.theme.HornInstrumentAccents
import com.industrial.horn.ui.theme.HornText
import com.industrial.horn.ui.theme.LocalHornColors
import com.industrial.horn.ui.theme.LocalReducedMotion
import com.industrial.horn.ui.theme.StartupTimeline
import com.industrial.horn.ui.theme.WakeSceneBrushes
import com.industrial.horn.ui.theme.drawWakeScene
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Cold-start calibration overlay (docs/spec/03 §11).
 *
 * The scene is a boot sequence, not a logo sting: scanlines, a four-corner reticle, a boot log,
 * a timecode, a turning dashed ring with a 360° sync arc, a machined dial and — at handoff —
 * vertical shutters closing onto a shrinking centre line.
 *
 * Everything drawn is f(elapsed) of one clock, read lazily inside draw/graphicsLayer lambdas, so
 * the frame loop never recomposes. Text is the only throttled element: the timecode refreshes on
 * a 60 ms clock and the wordmark's letter-spacing is static (animating tracking is a text-layout
 * operation and was a defect in the previous revision). Tapping anywhere skips on the first press
 * frame; the layer collapses within 120 ms and then leaves composition for good.
 */
@Composable
fun StartupOverlay(
    onSkip: () -> Unit,
    onRevealLatched: () -> Unit,
    onFinished: () -> Unit,
    exiting: Boolean,
    modifier: Modifier = Modifier,
    elapsedProvider: (() -> Float)? = null,
    onBloomHaptic: () -> Unit = {},
) {
    val skipHint = stringResource(R.string.startup_skip_hint)
    val cockpit = LocalHornColors.current
    val dark = cockpit.isDark
    val warm = HornInstrumentAccents.Car.resolve(dark)
    val reducedMotion = LocalReducedMotion.current || LocalInspectionMode.current

    val internalClock = remember { mutableFloatStateOf(0f) }
    if (elapsedProvider == null && !reducedMotion) {
        LaunchedEffect(Unit) {
            val origin = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { now ->
                    internalClock.floatValue = ((now - origin) / 1_000_000f).coerceAtMost(StartupTimeline.TOTAL_MS)
                }
            }
        }
    }
    // Spec §11.7: the provider stays fresh via rememberUpdatedState while the lambda identity
    // remains stable, so host recompositions never restart the latch polling loops.
    val currentProvider = rememberUpdatedState(elapsedProvider)
    val elapsed: () -> Float =
        remember(reducedMotion) {
            if (reducedMotion) {
                { StartupTimeline.TOTAL_MS }
            } else {
                { currentProvider.value?.invoke() ?: internalClock.floatValue }
            }
        }

    // Timeline latches: reveal fires exactly once; completion and the haptic tick are one-shots.
    val fired = remember { BooleanArray(3) }
    LaunchedEffect(elapsed) {
        while (isActive) {
            val t = elapsed()
            if (!fired[0] && t >= StartupTimeline.HAPTIC_MS) {
                fired[0] = true
                onBloomHaptic()
            }
            if (!fired[1] && t >= StartupTimeline.REVEAL_LATCH_MS) {
                fired[1] = true
                onRevealLatched()
            }
            if (t >= StartupTimeline.TOTAL_MS) {
                if (!fired[2]) {
                    fired[2] = true
                    onFinished()
                }
                break
            }
            delay(8)
        }
    }
    LaunchedEffect(exiting) {
        if (exiting) {
            delay(StartupTimeline.SKIP_COLLAPSE_MS.toLong())
            if (!fired[2]) {
                fired[2] = true
                onFinished()
            }
        }
    }

    val exitScale by animateFloatAsState(
        targetValue = if (exiting) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
        label = "startupExitScale",
    )
    val exitAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(StartupTimeline.SKIP_COLLAPSE_MS.toInt()),
        label = "startupExitAlpha",
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                // The overlay owns its surface: the app is already composed underneath, and a
                // transparent boot layer would flash the window background on some devices.
                .background(cockpit.canvas)
                .graphicsLayer {
                    val scale = exitScale
                    scaleX = scale
                    scaleY = scale
                    alpha = exitAlpha * StartupTimeline.overlayAlpha(elapsed())
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // First press frame skips: no need to wait for the finger to lift.
                        onSkip()
                    }
                }.semantics {
                    contentDescription = skipHint
                    role = Role.Button
                }.testTag("startup_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val brushes =
                            WakeSceneBrushes.of(
                                size = size,
                                center = Offset(size.width / 2f, size.height * 0.42f),
                                radius = kotlin.math.min(size.width, size.height) * 0.24f,
                                palette = cockpit,
                                warm = warm,
                                dark = dark,
                            )
                        onDrawBehind {
                            drawWakeScene(
                                t = elapsed(),
                                brushes = brushes,
                                palette = cockpit,
                                accent = warm,
                                dark = dark,
                            )
                        }
                    },
            onDraw = {},
        )

        BootLog(
            elapsed = elapsed,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 92.dp),
        )
        SequenceMark(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 92.dp),
        )
        WakeWordmark(elapsed = elapsed, accent = warm)
        SyncBar(
            elapsed = elapsed,
            accent = warm,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 62.dp),
        )

        Text(
            text = skipHint,
            color = cockpit.textTertiary,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        alpha = smoothFade(elapsed()) * exitAlpha
                    }.padding(bottom = 38.dp),
        )

        // Shutters are painted last so they close over the HUD and the wordmark.
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("startup_shutters"),
        ) {
            val progress = StartupTimeline.shutterProgress(elapsed())
            if (progress > 0f) {
                // The shutters close onto the instrument's optical centre, not the screen centre:
                // the handoff should collapse onto the dial that the main screen inherits.
                val axis = size.height * 0.42f
                val gap = 1.5.dp.toPx()
                val topHeight = (axis - gap) * progress
                val bottomHeight = (size.height - axis - gap) * progress
                val seam = cockpit.hairlineStrong.copy(alpha = 0.9f * progress)
                val shade =
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, cockpit.groove.copy(alpha = 0.28f)),
                    )
                drawRect(
                    color = cockpit.canvas,
                    topLeft = Offset.Zero,
                    size = Size(size.width, topHeight),
                )
                drawRect(
                    color = cockpit.canvas,
                    topLeft = Offset(0f, size.height - bottomHeight),
                    size = Size(size.width, bottomHeight),
                )
                // Seams make the closing panel edges legible; without them the masks are
                // invisible against the canvas and the handoff reads as a plain fade.
                drawLine(
                    color = seam,
                    start = Offset(0f, topHeight),
                    end = Offset(size.width, topHeight),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = seam,
                    start = Offset(0f, size.height - bottomHeight),
                    end = Offset(size.width, size.height - bottomHeight),
                    strokeWidth = 1.dp.toPx(),
                )
                drawRect(
                    brush = shade,
                    topLeft = Offset(0f, topHeight),
                    size = Size(size.width, 12.dp.toPx()),
                )
                drawRect(
                    brush = shade,
                    topLeft = Offset(0f, size.height - bottomHeight - 12.dp.toPx()),
                    size = Size(size.width, 12.dp.toPx()),
                )
            }
            val flash = StartupTimeline.flashAlpha(elapsed())
            if (flash > 0f) {
                drawRect(color = warm.copy(alpha = 0.12f * flash))
            }
            val line = StartupTimeline.centerLineWidth(elapsed())
            if (progress > 0f && line > 0f) {
                // The beam starts at dial width and narrows to nothing; a full-width line would
                // read as a progress bar, not as a handoff.
                val axis = size.height * 0.42f
                val halfWidth = size.width * 0.26f * (1f - 0.55f * progress) * line
                drawLine(
                    color = warm.copy(alpha = 0.75f * progress * line),
                    start = Offset(size.width / 2f - halfWidth, axis),
                    end = Offset(size.width / 2f + halfWidth, axis),
                    strokeWidth = 2.dp.toPx(),
                )
                drawLine(
                    color = warm.copy(alpha = 0.18f * progress * line),
                    start = Offset(size.width / 2f - halfWidth, axis),
                    end = Offset(size.width / 2f + halfWidth, axis),
                    strokeWidth = 10.dp.toPx(),
                )
            }
        }
    }
}

private fun smoothFade(t: Float): Float =
    if (t <= 300f) {
        0f
    } else {
        ((t - 300f) / 300f).coerceIn(0f, 1f) * StartupTimeline.overlayAlpha(t)
    }

/**
 * Three boot-log lines, 300 ms apart, in the cockpit's monospace micro-label style. Each line
 * only changes its layer alpha, so the text never re-lays out during the animation.
 */
@Composable
private fun BootLog(
    elapsed: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHornColors.current
    val lines =
        listOf(
            "SYS  ACOUSTIC CORE",
            "DSP  48 kHz · 2CH",
            "CAL  DIAL 60 · READY",
        )
    Column(modifier = modifier) {
        lines.forEachIndexed { index, line ->
            Text(
                text = line,
                color = colors.textTertiary,
                style = HornText.Data.copy(letterSpacing = 2.sp),
                maxLines = 1,
                modifier =
                    Modifier
                        .graphicsLayer {
                            alpha = StartupTimeline.bootLineAlpha(elapsed(), index)
                        },
            )
            if (index != lines.lastIndex) Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Sequence mark: a static, truthful token (the calibration is frozen at 1040 ms total). A live
 * timecode would have to tick on a text clock and would re-lay out every 60 ms for no
 * information a boot sequence needs.
 */
@Composable
private fun SequenceMark(modifier: Modifier = Modifier) {
    val colors = LocalHornColors.current
    Text(
        text = "SEQ 1.04 s",
        color = colors.textTertiary,
        style = HornText.Data.copy(letterSpacing = 2.sp),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * The wordmark is wiped in by a clip rectangle, not by animated letter-spacing: tracking is a
 * text-layout property, and the previous revision animated it (a discipline violation that also
 * re-laid out the text on every frame).
 */
@Composable
private fun WakeWordmark(
    elapsed: () -> Float,
    accent: Color,
) {
    val colors = LocalHornColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                // Layout offset (deferred read) instead of a layer translation: a RenderNode
                // translation can be clipped to the layer bounds on some renderers.
                .offset {
                    val reveal = StartupTimeline.wordmarkReveal(elapsed())
                    val entrance = (1f - reveal) * 18f * density
                    IntOffset(0, (104.dp.toPx() + entrance).roundToInt())
                },
    ) {
        Text(
            text = stringResource(R.string.brand_name),
            color = colors.textPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        val reveal = StartupTimeline.wordmarkReveal(elapsed())
                        clipRect(right = size.width * reveal) { this@drawWithContent.drawContent() }
                    },
        )
        Spacer(modifier = Modifier.height(9.dp))
        Canvas(
            modifier =
                Modifier
                    .width(96.dp)
                    .height(1.dp)
                    .drawWithContent {
                        drawContent()
                        val reveal = StartupTimeline.wordmarkReveal(elapsed())
                        if (reveal > 0f) {
                            drawLine(
                                color = accent.copy(alpha = 0.55f * reveal),
                                start = Offset(0f, 0.5f),
                                end = Offset(size.width * reveal, 0.5f),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    },
            onDraw = {},
        )
    }
}

/** Bottom sync bar: calibration progress plus the reveal mark at 820 ms. */
@Composable
private fun SyncBar(
    elapsed: () -> Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHornColors.current
    Canvas(
        modifier =
            modifier
                .width(168.dp)
                .height(6.dp),
    ) {
        val progress = StartupTimeline.progress(elapsed())
        val trackY = 1.dp.toPx()
        drawLine(
            color = colors.hairline,
            start = Offset(0f, trackY),
            end = Offset(size.width, trackY),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = accent.copy(alpha = 0.85f),
            start = Offset(0f, trackY),
            end = Offset(size.width * progress, trackY),
            strokeWidth = 1.5.dp.toPx(),
        )
        val markX = size.width * (StartupTimeline.REVEAL_LATCH_MS / StartupTimeline.TOTAL_MS)
        drawLine(
            color = colors.textTertiary,
            start = Offset(markX, 0f),
            end = Offset(markX, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
}
