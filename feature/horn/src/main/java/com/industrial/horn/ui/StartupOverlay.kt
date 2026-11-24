package com.industrial.horn.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.industrial.horn.ui.theme.StartupFilmScene
import com.industrial.horn.ui.theme.StartupHeroLayout
import com.industrial.horn.ui.theme.StartupTimeline
import com.industrial.horn.ui.theme.drawStartupFilm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Cold-start film overlay (docs/spec/03 §11.1, ADR-0010 v5.0).
 *
 * Six acts on one clock: pulse → identity → drawing (contour fragments assemble the brand mark) →
 * colorize → portrait → shutter handoff. The scene is drawn by [drawStartupFilm] inside a single
 * `drawWithCache` canvas, so the frame loop never recomposes; the HUD text layers only change their
 * layer alpha (no per-frame text layout), and the shutter squeezes the whole scene onto the optical
 * axis while the boot surface dissolves, revealing the real interface underneath.
 *
 * Tap anywhere skips on the press frame; the layer collapses within [StartupTimeline.SKIP_COLLAPSE_MS]
 * and then leaves composition for good. Reveal fires exactly once, at the shutter boundary.
 */
@Composable
fun StartupOverlay(
    onSkip: () -> Unit,
    onRevealLatched: () -> Unit,
    onFinished: () -> Unit,
    exiting: Boolean,
    modifier: Modifier = Modifier,
    elapsedProvider: (() -> Float)? = null,
    onMilestoneHaptic: () -> Unit = {},
    // 测试注入点：不给就异步自载（生产路径），给了就用外部解码结果（定格帧需要确定性）。
    heroOverride: StartupHero? = null,
    heroImageOverride: ImageBitmap? = null,
) {
    val skipHint = stringResource(R.string.startup_skip_hint)
    val cockpit = LocalHornColors.current
    val dark = cockpit.isDark
    val warm = HornInstrumentAccents.Car.resolve(dark)
    val reducedMotion = LocalReducedMotion.current || LocalInspectionMode.current

    // 参考图与其线稿素材在组合期异步载入：首帧不被 633 KB 解码拖住（预热纪律），
    // 而它们的最晚使用点是 IDENTITY(1.0 s) / COLORIZE(7.2 s)，留了几秒的余量。
    val context = LocalContext.current
    val loadedHero by
        produceState(StartupHero(emptyList(), StartupHero.FALLBACK_ASPECT, 0, 0), heroOverride) {
            if (heroOverride == null) {
                value = withContext(Dispatchers.IO) { StartupHero.load(context) }
            }
        }
    val loadedHeroImage by
        produceState<ImageBitmap?>(null, heroImageOverride) {
            if (heroImageOverride == null) {
                value =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            BitmapFactory.decodeResource(context.resources, R.drawable.startup_hero)?.asImageBitmap()
                        }.getOrNull()
                    }
            }
        }
    val heroAsset = heroOverride ?: loadedHero
    val heroImage = heroImageOverride ?: loadedHeroImage

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
    // The provider stays fresh via rememberUpdatedState while the lambda identity remains stable,
    // so host recompositions never restart the latch polling loops.
    val currentProvider = rememberUpdatedState(elapsedProvider)
    val elapsed: () -> Float =
        remember(reducedMotion) {
            if (reducedMotion) {
                { StartupTimeline.REDUCED_MOTION_FRAME_MS }
            } else {
                { currentProvider.value?.invoke() ?: internalClock.floatValue }
            }
        }

    // Timeline latches: haptic and reveal fire exactly once; completion is one-shot as well.
    val fired = remember { BooleanArray(3) }
    LaunchedEffect(elapsed, reducedMotion) {
        if (reducedMotion) {
            // Reduced motion is a still frame, not a silent 10 s hold: retire the layer early.
            delay(StartupTimeline.REDUCED_MOTION_HOLD_MS.toLong())
            if (!fired[2]) {
                fired[2] = true
                onFinished()
            }
            return@LaunchedEffect
        }
        while (isActive) {
            val t = elapsed()
            if (!fired[0] && t >= StartupTimeline.HAPTIC_MS) {
                fired[0] = true
                onMilestoneHaptic()
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

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // First press frame skips: no need to wait for the finger to lift.
                        onSkip()
                    }
                }.semantics {
                    contentDescription = skipHint
                    role = Role.Button
                }.testTag(STARTUP_OVERLAY_TAG),
    ) {
        val axisFraction = StartupFilmScene.OPTICAL_AXIS
        val aspect = heroAsset.aspect
        // Anchors come from the reference-image box, so the wordmark and captions sit under the
        // picture on every window size class without reading size inside the frame loop.
        val heroHeight = StartupHeroLayout.subjectHeight(maxWidth.value, maxHeight.value, aspect)
        val heroBottomFromTop = maxHeight.value * axisFraction + heroHeight / 2f
        val heroBottomFromCenter = heroBottomFromTop - maxHeight.value / 2f
        val wordmarkOffset = (heroBottomFromCenter + 46f).dp
        val captionOffset = (heroBottomFromCenter + 92f).dp
        val density = LocalDensity.current

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = exitScale
                        scaleY = exitScale
                        alpha = exitAlpha
                    },
        ) {
            // Everything that belongs to the film collapses onto the optical axis at the handoff.
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleY =
                                (
                                    1f -
                                        if (reducedMotion) {
                                            0f
                                        } else {
                                            StartupTimeline.shutterCollapse(elapsed())
                                        }
                                ).coerceAtLeast(0.001f)
                            transformOrigin = TransformOrigin(0.5f, axisFraction)
                        },
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val scene =
                                    StartupFilmScene.of(
                                        size = size,
                                        density = density.density,
                                        palette = cockpit,
                                        accent = warm,
                                        dark = dark,
                                        polylines = heroAsset.polylines,
                                        heroAspect = heroAsset.aspect,
                                    )
                                onDrawBehind {
                                    drawStartupFilm(
                                        t = elapsed(),
                                        scene = scene,
                                        palette = cockpit,
                                        accent = warm,
                                        dark = dark,
                                        reduced = reducedMotion,
                                        hero = heroImage,
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
                WakeWordmark(
                    elapsed = elapsed,
                    accent = warm,
                    offsetFromCenter = wordmarkOffset,
                )
                PortraitCaptions(
                    elapsed = elapsed,
                    offsetFromCenter = captionOffset,
                )
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
            }

            // The seam and the flash live outside the squeezing layer: the beam pinches shut
            // horizontally while the scene collapses vertically, which is the actual handoff.
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = exitAlpha }
                        .testTag(STARTUP_SHUTTERS_TAG),
            ) {
                val t = elapsed()
                if (reducedMotion) return@Canvas
                val axis = size.height * axisFraction
                val pinch = StartupTimeline.centerLinePinch(t)
                if (pinch > 0f && pinch < 1f) {
                    val halfWidth = size.width * 0.5f * (1f - pinch)
                    if (halfWidth > 0.5f) {
                        drawLine(
                            color = warm.copy(alpha = 0.16f * (1f - pinch)),
                            start = Offset(size.width / 2f - halfWidth, axis),
                            end = Offset(size.width / 2f + halfWidth, axis),
                            strokeWidth = 10.dp.toPx(),
                        )
                        drawLine(
                            color = warm.copy(alpha = 0.80f * (1f - pinch)),
                            start = Offset(size.width / 2f - halfWidth, axis),
                            end = Offset(size.width / 2f + halfWidth, axis),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
                val flash = StartupTimeline.flashAlpha(t)
                if (flash > 0f) {
                    drawRect(color = cockpit.textPrimary.copy(alpha = 0.10f * flash))
                    drawRect(color = warm.copy(alpha = 0.12f * flash))
                }
            }
        }
    }
}

private fun smoothFade(t: Float): Float =
    if (t <= 600f) {
        0f
    } else {
        ((t - 600f) / 400f).coerceIn(0f, 1f) * StartupTimeline.overlayAlpha(t)
    }

/**
 * Three boot-log lines, 400 ms apart, in the cockpit's monospace micro-label style. Each line only
 * changes its layer alpha, so the text never re-lays out during the animation.
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
 * Sequence mark: a static, truthful token (the film is frozen at 10.40 s). A live timecode would
 * have to tick on a text clock and re-lay out every 60 ms for no information a boot sequence needs.
 */
@Composable
private fun SequenceMark(modifier: Modifier = Modifier) {
    val colors = LocalHornColors.current
    Text(
        text = "SEQ 10.40 s",
        color = colors.textTertiary,
        style = HornText.Data.copy(letterSpacing = 2.sp),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * The wordmark is wiped in by a clip rectangle, not by animated letter-spacing: tracking is a
 * text-layout property and animating it re-lays out the text on every frame.
 */
@Composable
private fun WakeWordmark(
    elapsed: () -> Float,
    accent: Color,
    offsetFromCenter: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalHornColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                // Layout offset (deferred read) instead of a layer translation: a RenderNode
                // translation can be clipped to the layer bounds on some renderers.
                .offset {
                    val reveal = StartupTimeline.titleReveal(elapsed())
                    val entrance = (1f - reveal) * 18f * density
                    IntOffset(0, (offsetFromCenter.toPx() + entrance).roundToInt())
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
                        val reveal = StartupTimeline.titleReveal(elapsed())
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
                        val reveal = StartupTimeline.titleReveal(elapsed())
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

/**
 * PORTRAIT captions: two HUD lines that fade in 600 ms apart. Text is static, only the layer alpha
 * changes — the "UI and visuals run on different clocks" rule of the skill.
 */
@Composable
private fun PortraitCaptions(
    elapsed: () -> Float,
    offsetFromCenter: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalHornColors.current
    val captions =
        listOf(
            stringResource(R.string.brand_tagline),
            stringResource(R.string.startup_caption_ready),
        )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset { IntOffset(0, offsetFromCenter.roundToPx()) },
    ) {
        captions.forEachIndexed { index, caption ->
            Text(
                text = caption,
                color = colors.textTertiary,
                style = HornText.Data.copy(letterSpacing = 2.sp, fontSize = 9.sp),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = StartupTimeline.captionAlpha(elapsed(), index)
                    },
            )
            if (index != captions.lastIndex) Spacer(modifier = Modifier.height(3.dp))
        }
    }
}

/** Bottom sync bar: milestone pacing plus the reveal-latch tick (9.6 s of 10.4 s). */
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
        val progress = StartupTimeline.syncProgress(elapsed())
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

internal const val STARTUP_OVERLAY_TAG = "startup_overlay"
internal const val STARTUP_SHUTTERS_TAG = "startup_shutters"
