package com.industrial.horn.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.industrial.horn.ui.theme.AppleMotion
import com.industrial.horn.ui.theme.HornCream
import kotlin.math.abs
import kotlin.math.min

/**
 * Tactile glass horn orb — the one button that matters.
 *
 * Gesture design (unchanged since v2, proven by UI tests):
 *  - `awaitEachGesture` serializes presses and is the Compose-recommended low-level primitive.
 *  - `waitForUpOrCancellation` owns the release/cancel path, including system cancellation.
 *  - A second pointer observer runs on the Initial pass and consumes a move once the active
 *  finger leaves the circular hit area. That makes `waitForUpOrCancellation` return null,
 *  which the gesture handler maps to `onPressCancel` immediately, not on lift.
 *
 * Visual design (v3.5 Apple Glass): concentric glass halo rings, a frosted optical body with
 * directional specular light, and an RMS-driven pressure glow. While holding, an iOS-style
 * vertical glass capsule mirrors the Expression value with rubber-band resistance at the limits.
 * High-frequency values arrive as `() -> Float` providers and are read inside draw lambdas, so
 * press/drag/RMS updates redraw without recomposition.
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
    rmsProvider: () -> Float,
    expressionProvider: () -> Float,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
    onExpressionChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pressScale by animateFloatAsState(
        targetValue = if (isHonking) 0.945f else 1f,
        animationSpec = AppleMotion.TactileOrbSpring,
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
                        Brush.radialGradient(
                            colors = listOf(GlassBodyLight, GlassBodyDark),
                            center = Offset(diameter.value * 0.36f, diameter.value * 0.30f),
                            radius = diameter.value * 0.85f,
                        ),
                    shape = CircleShape,
                ).border(width = 1.dp, color = GlassRim, shape = CircleShape)
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
        Canvas(modifier = Modifier.matchParentSize()) {
            drawGlassOrb(
                accent = accent,
                glow = glow,
                rms = rmsProvider(),
                expression = expressionProvider(),
            )
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

/**
 * Layered orb drawing: concentric glass halo, frosted body, directional specular highlight,
 * RMS pressure glow and the restrained vertical expression capsule.
 */
private fun DrawScope.drawGlassOrb(
    accent: Color,
    glow: Float,
    rms: Float,
    expression: Float,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) / 2f
    val pressure = rms.coerceIn(0f, 1f)

    // Outer halo is tied to the pressed state rather than the infinite wave animation.
    drawCircle(
        color = accent.copy(alpha = (0.07f + pressure * 0.24f) * glow),
        radius = radius * 0.98f,
        center = center,
    )
    // Concentric glass halo rings.
    drawCircle(
        color = Color.White.copy(alpha = 0.12f),
        radius = radius * 0.93f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = GlassSpecular.copy(alpha = 0.30f),
        radius = radius * 0.845f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    // Directional specular light from the top-left, like a real glass dome.
    drawCircle(
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.02f),
                        Color.Transparent,
                    ),
                start = Offset(size.width * 0.18f, size.height * 0.12f),
                end = Offset(size.width * 0.86f, size.height * 0.92f),
            ),
        radius = radius * 0.80f,
        center = center,
    )
    // RMS pressure glow rising from the core while honking.
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        accent.copy(alpha = (0.18f + pressure * 0.30f) * glow),
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
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.22f),
        radius = radius * 0.70f,
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )

    // Vertical glass Expression capsule: appears only while holding, rubber-banded at the ends.
    if (glow > 0.05f) {
        drawExpressionCapsule(
            accent = accent,
            glow = glow,
            expression = expression,
            center = center,
            radius = radius,
        )
    }
}

private fun DrawScope.drawExpressionCapsule(
    accent: Color,
    glow: Float,
    expression: Float,
    center: Offset,
    radius: Float,
) {
    val capsuleWidth = 7.dp.toPx()
    val halfHeight = radius * 0.30f
    val x = center.x + radius * 0.80f
    val top = center.y - halfHeight

    // Glass trough.
    drawRoundRect(
        color = Color.White.copy(alpha = 0.12f * glow),
        topLeft = Offset(x - capsuleWidth / 2f, top),
        size = Size(capsuleWidth, halfHeight * 2f),
        cornerRadius = CornerRadius(capsuleWidth / 2f),
    )
    // Fill from center to the current expression, visually stretched past the limits.
    val stretch = AppleMotion.rubberBand(expression, limit = 1f)
    val fillHalf = halfHeight * stretch.coerceIn(-1.12f, 1.12f)
    val fillTop = if (fillHalf >= 0f) center.y - fillHalf else center.y
    drawRoundRect(
        color = accent.copy(alpha = (if (abs(expression) > 1f) 0.55f else 0.9f) * glow),
        topLeft = Offset(x - capsuleWidth / 2f, fillTop),
        size = Size(capsuleWidth, abs(fillHalf).coerceAtLeast(1.5f)),
        cornerRadius = CornerRadius(capsuleWidth / 2f),
    )
    // Restrained center tick marks.
    for (tick in -1..1) {
        val y = center.y - tick * halfHeight * 0.62f
        drawLine(
            color = Color.White.copy(alpha = 0.30f * glow),
            start = Offset(x - capsuleWidth * 0.9f, y),
            end = Offset(x + capsuleWidth * 0.9f, y),
            strokeWidth = 1.dp.toPx(),
        )
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

private val GlassBodyLight = Color(0xFF3A444D)
private val GlassBodyDark = Color(0xFF12181D)
private val GlassRim = Color(0xFF8B98A2)
private val GlassSpecular = Color(0xFFDCE6EC)
