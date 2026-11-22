package com.industrial.horn.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.sp
import com.industrial.horn.ui.theme.HornCream
import kotlin.math.min

/**
 * Physical horn button.
 *
 * Gesture design:
 *  - `awaitEachGesture` serializes presses and is the Compose-recommended low-level primitive.
 *  - `waitForUpOrCancellation` owns the release/cancel path, including system cancellation.
 *  - A second pointer observer runs on the Initial pass and consumes a move once the active
 *    finger leaves the circular hit area. That makes `waitForUpOrCancellation` return null,
 *    which the gesture handler maps to `onPressCancel` immediately, not on lift.
 *
 * The circular check is intentional: a square composable bounds check would keep honking in the
 * transparent corners of the button.
 */
@Composable
fun HornButton(
    isHonking: Boolean,
    contentDescription: String,
    stateDescription: String,
    label: String,
    diameter: Dp,
    accent: Color,
    @DrawableRes iconRes: Int,
    rms: Float,
    expression: Float,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
    onExpressionChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pressScale by animateFloatAsState(
        targetValue = if (isHonking) 0.945f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "hornPressScale",
    )
    val glow by animateFloatAsState(
        targetValue = if (isHonking) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "hornGlow",
    )
    val tracker = remember { PressTracker() }

    Box(
        modifier =
            modifier
                .size(diameter)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }.shadow(
                    elevation = if (isHonking) 7.dp else 18.dp,
                    shape = CircleShape,
                    clip = false,
                ).clip(CircleShape)
                .background(
                    brush =
                        Brush.linearGradient(
                            colors = listOf(MetalLight, MetalDark),
                            start = Offset.Zero,
                            end = Offset(diameter.value, diameter.value),
                        ),
                    shape = CircleShape,
                ).border(width = 1.dp, color = MetalRim, shape = CircleShape)
                .onSizeChanged {
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
                                if (kotlin.math.abs(next - tracker.lastExpression) > 0.02f) {
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
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) / 2f

            // Outer halo is tied to the pressed state rather than the infinite wave animation.
            drawCircle(
                color = accent.copy(alpha = (0.07f + rms.coerceIn(0f, 1f) * 0.24f) * glow),
                radius = radius * 0.98f,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = radius * 0.93f,
                center = center,
                style =
                    androidx.compose.ui.graphics.drawscope
                        .Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = MetalHighlight.copy(alpha = 0.28f),
                radius = radius * 0.845f,
                center = center,
                style =
                    androidx.compose.ui.graphics.drawscope
                        .Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                accent.copy(alpha = 0.20f * glow),
                                Color.Transparent,
                            ),
                        center = Offset(size.width * 0.34f, size.height * 0.30f),
                        radius = radius * 0.82f,
                    ),
                radius = radius * 0.80f,
                center = center,
            )
            drawCircle(
                color = accent.copy(alpha = 0.42f),
                radius = radius * 0.755f,
                center = center,
                style =
                    androidx.compose.ui.graphics.drawscope
                        .Stroke(width = 1.5.dp.toPx()),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.20f),
                radius = radius * 0.70f,
                center = center,
                style =
                    androidx.compose.ui.graphics.drawscope
                        .Stroke(width = 2.dp.toPx()),
            )

            // Restrained vertical pitch/air-pressure gauge. It appears only while holding.
            if (glow > 0.05f) {
                val gaugeX = center.x + radius * 0.79f
                val gaugeHalf = radius * 0.22f
                val clampedExpression = expression.coerceIn(-1f, 1f)
                for (tick in -3..3) {
                    val position = tick / 3f
                    val y = center.y - position * gaugeHalf
                    val activeTick = kotlin.math.abs(position - clampedExpression) < 0.22f
                    drawLine(
                        color =
                            if (activeTick) {
                                accent.copy(alpha = 0.95f * glow)
                            } else {
                                Color.White.copy(alpha = 0.24f * glow)
                            },
                        start = Offset(gaugeX, y),
                        end = Offset(gaugeX + if (activeTick) 7.dp.toPx() else 4.dp.toPx(), y),
                        strokeWidth = if (activeTick) 2.dp.toPx() else 1.dp.toPx(),
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Crossfade(
                targetState = iconRes,
                animationSpec = tween(durationMillis = 280),
                label = "hornVoiceIcon",
            ) { currentIcon ->
                Icon(
                    painter = painterResource(currentIcon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(diameter * 0.32f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                color = HornCream,
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
            )
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

private val MetalLight = Color(0xFF46515A)
private val MetalDark = Color(0xFF141A1F)
private val MetalRim = Color(0xFF7C8993)
private val MetalHighlight = Color(0xFFD9E2E8)
