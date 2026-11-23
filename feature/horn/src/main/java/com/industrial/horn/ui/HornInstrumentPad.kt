package com.industrial.horn.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.industrial.horn.ui.theme.HornMotion
import com.industrial.horn.ui.theme.HornText
import com.industrial.horn.ui.theme.InstrumentDial
import com.industrial.horn.ui.theme.LocalHornColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The one control that matters: a machined instrument dial.
 *
 * Gesture contract (unchanged since v2, proven by UI tests):
 *  - `awaitEachGesture` serializes presses and is the Compose-recommended low-level primitive.
 *  - `waitForUpOrCancellation` owns the release/cancel path, including system cancellation.
 *  - A second pointer observer runs on the Initial pass and consumes a move once the active
 *    finger leaves the circular hit area. That makes `waitForUpOrCancellation` return null,
 *    which the gesture handler maps to `onPressCancel` immediately, not on lift.
 *
 * Visual contract (v4.0 cockpit): turned-metal disc, knurled rim, 240° expression bezel and the
 * 60-tick RMS pressure ring. Press travel is a 1.5 % scale that stops instantly; the only
 * continuous readout is the expression value, refreshed on a 60 ms text clock because changing a
 * `Text` string is a layout operation, not a frame-clock operation.
 */
@Composable
fun HornInstrumentPad(
    isHonking: Boolean,
    contentDescription: String,
    stateDescription: String,
    label: String,
    diameter: Dp,
    accent: Color,
    @DrawableRes iconRes: Int,
    rmsProvider: () -> Float,
    expressionProvider: () -> Float,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
    onExpressionChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHornColors.current
    val pressScale by animateFloatAsState(
        targetValue = if (isHonking) 0.985f else 1f,
        animationSpec = HornMotion.KeySpring,
        label = "padTravel",
    )
    val tracker = remember { PressTracker() }
    var expressionReadout by remember { mutableIntStateOf(0) }

    // Text layout cannot run on the frame clock, so the readout is sampled on a 60 ms clock and
    // only while the horn is held. Draw layers stay on deferred reads.
    LaunchedEffect(isHonking) {
        if (!isHonking) {
            expressionReadout = 0
            return@LaunchedEffect
        }
        while (isActive) {
            expressionReadout = (expressionProvider() * 100f).roundToInt()
            delay(60)
        }
    }

    Box(
        modifier =
            modifier
                .size(diameter)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }.onSizeChanged {
                    tracker.size = Size(it.width.toFloat(), it.height.toFloat())
                }.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (!tracker.pressed) continue
                            val pointerId = tracker.activePointerId ?: continue
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                            if (!isInsideCircle(change.position, tracker.size)) {
                                change.consume()
                            } else if (tracker.size.height > 0f) {
                                val centerY = tracker.size.height / 2f
                                val travel = tracker.size.height * 0.38f
                                val next =
                                    ((centerY - change.position.y) / travel)
                                        .coerceIn(-1f, 1f)
                                if (abs(next - tracker.lastExpression) > 0.02f) {
                                    tracker.lastExpression = next
                                    onExpressionChanged(next)
                                }
                            }
                        }
                    }
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.activePointerId = down.id
                        tracker.pressed = true
                        var terminalCallbackHandled = false
                        try {
                            down.consume()
                            tracker.lastExpression = 0f
                            onPressStart()
                            onExpressionChanged(0f)
                            val up = waitForUpOrCancellation()
                            terminalCallbackHandled = true
                            if (up != null) {
                                onPressEnd()
                            } else {
                                onPressCancel()
                            }
                        } finally {
                            // Covers coroutine cancellation from disposal as well as pointer cancel.
                            if (!terminalCallbackHandled) onPressCancel()
                            tracker.pressed = false
                            tracker.activePointerId = null
                            tracker.lastExpression = 0f
                            onExpressionChanged(0f)
                        }
                    }
                }.semantics(mergeDescendants = true) {
                    role = Role.Button
                    this.contentDescription = contentDescription
                    this.stateDescription = stateDescription
                }.testTag("horn_button"),
        contentAlignment = Alignment.Center,
    ) {
        InstrumentDial(
            diameter = diameter,
            accent = accent,
            active = isHonking,
            pressed = isHonking,
            rmsProvider = rmsProvider,
            expressionProvider = expressionProvider,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Crossfade(
                    targetState = iconRes,
                    animationSpec = tween(durationMillis = 240),
                    label = "hornVoiceIcon",
                ) { currentIcon ->
                    Icon(
                        painter = painterResource(currentIcon),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(diameter * 0.20f),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = label,
                    color = colors.textPrimary,
                    style = HornText.PlateLabel,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text =
                        if (isHonking) {
                            val sign = if (expressionReadout >= 0) "+" else "−"
                            "EXP $sign${abs(expressionReadout).toString().padStart(2, '0')}"
                        } else {
                            "EXP ±00"
                        },
                    color = if (isHonking) accent else colors.textTertiary,
                    style = HornText.Data,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("expression_readout"),
                )
            }
        }
    }
}

private class PressTracker {
    var pressed: Boolean = false
    var activePointerId: PointerId? = null
    var lastExpression: Float = 0f
    var size: Size = Size.Zero
}

private fun isInsideCircle(
    position: Offset,
    size: Size,
): Boolean {
    if (size.width <= 0f || size.height <= 0f) return true
    val radius = min(size.width, size.height) / 2f
    val dx = position.x - size.width / 2f
    val dy = position.y - size.height / 2f
    return dx * dx + dy * dy <= radius * radius
}
