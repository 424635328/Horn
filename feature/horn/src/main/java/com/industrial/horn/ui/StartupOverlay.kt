package com.industrial.horn.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.industrial.horn.ui.theme.LocalHornColors
import com.industrial.horn.ui.theme.StartupTimeline
import com.industrial.horn.ui.theme.WakeSceneBrushes
import com.industrial.horn.ui.theme.drawWakeScene
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Optical Wake overlay (see docs/spec/03 §"Optical Wake").
 *
 * Everything drawn is f(elapsed) of one clock, read lazily inside draw/graphicsLayer lambdas —
 * zero recomposition at frame rate. Tapping anywhere on the very first press frame requests a
 * skip; the caller flips [exiting] and the layer collapses within 120 ms, then leaves composition
 * for good (no ghost mask may intercept horn gestures afterwards).
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
    val internalClock = remember { mutableFloatStateOf(0f) }
    if (elapsedProvider == null) {
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
        remember {
            { currentProvider.value?.invoke() ?: internalClock.floatValue }
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

    val cockpit = LocalHornColors.current
    val dark = cockpit.isDark
    val deep = if (dark) cockpit.textTertiary else cockpit.hairlineStrong
    // Brand amber resolves per palette so the calibration scene keeps AA contrast on carbon and
    // on ceramic alike; it is the single place the startup borrows the Car instrument accent.
    val warm = HornInstrumentAccents.Car.resolve(dark)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                // The overlay owns its surface: the app is already composed underneath, and a
                // transparent boot layer would flash the window background on some devices.
                .background(cockpit.canvas)
                .graphicsLayer {
                    val t = elapsed()
                    val scale = exitScale * StartupTimeline.overlayScale(t)
                    scaleX = scale
                    scaleY = scale
                    alpha = exitAlpha * StartupTimeline.overlayAlpha(t)
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
                                center =
                                    androidx.compose.ui.geometry
                                        .Offset(size.width / 2f, size.height * 0.42f),
                                radius = kotlin.math.min(size.width, size.height) * 0.24f,
                                deep = deep,
                                warm = warm,
                                dark = dark,
                                faceTop = cockpit.dialTop,
                                faceBottom = cockpit.dialBottom,
                            )
                        onDrawBehind {
                            drawWakeScene(
                                t = elapsed(),
                                brushes = brushes,
                                accent = warm,
                                deep = deep,
                                warm = warm,
                                dark = dark,
                                tickColor = cockpit.textTertiary,
                            )
                        }
                    },
            onDraw = {},
        )

        WakeWordmark(elapsed = elapsed)

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
    }
}

private fun smoothFade(t: Float): Float =
    if (t <= 300f) {
        0f
    } else {
        ((t - 300f) / 300f).coerceIn(0f, 1f) * (1f - StartupTimeline.mergeProgress(t))
    }

/**
 * The wordmark settles its letter-spacing during BLOOM. Letter-spacing is a text-layout
 * property, so — per the startup-animation discipline — this one element throttles its updates
 * to 60 ms while every purely visual property elsewhere stays on the frame clock. Isolated in
 * its own composable so the throttled recompositions never touch the canvas layer.
 */
@Composable
private fun WakeWordmark(elapsed: () -> Float) {
    var spacing by remember { mutableFloatStateOf(12f) }
    LaunchedEffect(elapsed) {
        while (isActive) {
            val t = elapsed()
            spacing = 12f - 8f * StartupTimeline.bloomProgress(t)
            if (t >= StartupTimeline.TOTAL_MS) break
            delay(60)
        }
    }
    Text(
        text = stringResource(R.string.brand_name),
        color = LocalHornColors.current.textPrimary,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = spacing.sp,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                // Layout offset (deferred read) instead of a layer translation: a RenderNode
                // translation can be clipped to the layer bounds on some renderers, which made
                // the wordmark vanish on the pre-MERGE frames. A layout offset never clips.
                .offset {
                    val settle = StartupTimeline.bloomProgress(elapsed())
                    val entrance = (1f - settle) * 22f * density
                    IntOffset(0, (132.dp.toPx() + entrance).roundToInt())
                }.graphicsLayer {
                    val settle = StartupTimeline.bloomProgress(elapsed())
                    alpha = StartupTimeline.wakeAlpha(elapsed()) * (0.45f + 0.55f * settle)
                },
    )
}
