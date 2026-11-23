package com.industrial.horn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The machined dial of the cockpit: a turned-metal disc, a knurled rim, four fastener heads, the
 * 240° expression bezel and the 60-tick RMS pressure ring around the outside.
 *
 * The needle is drawn by [PrecisionExpressionGauge] and its pivot is hidden behind the opaque
 * [content] hub — exactly how a real moving-coil meter covers its pivot with the dial plate. That
 * keeps the needle clear of the icon and readout at every expression value.
 *
 * Nothing animates on its own: the pressure ring reads [rmsProvider] and the bezel reads
 * [expressionProvider] inside the draw lambdas, so holding the horn redraws at frame rate without
 * a single recomposition; when the instrument is idle the subtree is frame-idle.
 */
@Composable
fun InstrumentDial(
    diameter: Dp,
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    rmsProvider: () -> Float = { 0f },
    expressionProvider: () -> Float = { 0f },
    showGauge: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = LocalHornColors.current
    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val radius = min(size.width, size.height) / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        // The face runs exactly up to the expression bezel: a gap here would read
                        // as a black tyre instead of a machined ring.
                        val face = radius * 0.858f
                        val body =
                            Brush.radialGradient(
                                colors = listOf(colors.dialTop, colors.dialBottom),
                                center = Offset(center.x - face * 0.34f, center.y - face * 0.40f),
                                radius = face * 1.9f,
                            )
                        val activeHalo =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        accent.copy(alpha = 0.16f),
                                        accent.copy(alpha = 0.05f),
                                        Color.Transparent,
                                    ),
                                center = center,
                                radius = face * 1.12f,
                            )
                        val pressShade =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        Color.Black.copy(alpha = 0.22f),
                                        Color.Black.copy(alpha = 0.04f),
                                        Color.Transparent,
                                    ),
                                center = center,
                                radius = face,
                            )
                        onDrawBehind {
                            if (active) {
                                drawCircle(brush = activeHalo, radius = face * 1.12f, center = center)
                            }

                            // --- turned face -------------------------------------------------
                            drawCircle(brush = body, radius = face, center = center)
                            // Concentric turning marks: 1 px, alternating, very low contrast.
                            var ring = 0.22f
                            var index = 0
                            while (ring < 1f) {
                                drawCircle(
                                    color =
                                        if (index % 2 == 0) {
                                            colors.plateHighlight.copy(alpha = if (colors.isDark) 0.07f else 0.55f)
                                        } else {
                                            colors.groove.copy(alpha = if (colors.isDark) 0.30f else 0.12f)
                                        },
                                    radius = face * ring,
                                    center = center,
                                    style = Stroke(width = 1f),
                                )
                                ring += 0.05f
                                index++
                            }
                            // Knurled rim: 72 short radial cuts.
                            val knurlInner = face * 0.90f
                            for (cut in 0 until 72) {
                                val angle = cut * (360f / 72f) * (PI.toFloat() / 180f)
                                val cosA = cos(angle)
                                val sinA = sin(angle)
                                drawLine(
                                    color = colors.textTertiary.copy(alpha = if (colors.isDark) 0.22f else 0.26f),
                                    start = Offset(center.x + cosA * knurlInner, center.y + sinA * knurlInner),
                                    end = Offset(center.x + cosA * face, center.y + sinA * face),
                                    strokeWidth = 1f,
                                )
                            }
                            drawCircle(
                                color = colors.hairlineStrong,
                                radius = face,
                                center = center,
                                style = Stroke(width = 1.4.dp.toPx()),
                            )
                            drawCircle(
                                color = colors.dialBottom.copy(alpha = 0.9f),
                                radius = face - 1.dp.toPx(),
                                center = center,
                                style = Stroke(width = 1.dp.toPx()),
                            )
                            // Lit top-left rim: the single strongest "this is metal" cue.
                            drawArc(
                                color = colors.plateHighlight.copy(alpha = if (colors.isDark) 0.45f else 1f),
                                startAngle = 155f,
                                sweepAngle = 110f,
                                useCenter = false,
                                topLeft = Offset(center.x - face + 1.dp.toPx(), center.y - face + 1.dp.toPx()),
                                size = Size((face - 1.dp.toPx()) * 2f, (face - 1.dp.toPx()) * 2f),
                                style = Stroke(width = 1.6.dp.toPx()),
                            )

                            // --- four fastener heads --------------------------------------
                            drawFasteners(center = center, radius = face * 0.86f, colors = colors)

                            if (pressed) {
                                drawCircle(brush = pressShade, radius = face, center = center)
                            }

                            // --- 60-tick RMS pressure ring --------------------------------
                            drawPressureTicks(
                                rms = rmsProvider(),
                                accent = accent,
                                colors = colors,
                            )
                        }
                    },
        )
        if (showGauge) {
            PrecisionExpressionGauge(
                expressionProvider = expressionProvider,
                accent = accent,
                active = active,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Opaque dial plate: covers the needle pivot and carries the icon + readout.
        Box(
            modifier =
                Modifier
                    .size(diameter * 0.46f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(colors.panelRaised, colors.panelRaised),
                        ),
                    ).border(1.dp, colors.hairlineStrong, CircleShape)
                    .drawWithCache {
                        onDrawBehind {
                            drawArc(
                                color = colors.groove.copy(alpha = 0.55f),
                                startAngle = 150f,
                                sweepAngle = 240f,
                                useCenter = false,
                                topLeft = Offset(1f, 1f),
                                size = Size(size.width - 2f, size.height - 2f),
                                style = Stroke(width = 1.2.dp.toPx()),
                            )
                            drawArc(
                                color = colors.plateHighlight.copy(alpha = if (colors.isDark) 0.28f else 1f),
                                startAngle = -30f,
                                sweepAngle = 240f,
                                useCenter = false,
                                topLeft = Offset(1f, 1f),
                                size = Size(size.width - 2f, size.height - 2f),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private fun DrawScope.drawFasteners(
    center: Offset,
    radius: Float,
    colors: HornColors,
) {
    val headRadius = radius * 0.035f
    for (index in 0 until 4) {
        val angle = (45f + index * 90f) * (PI.toFloat() / 180f)
        val position =
            Offset(
                center.x + cos(angle) * radius,
                center.y + sin(angle) * radius,
            )
        drawCircle(color = colors.dialBottom, radius = headRadius, center = position)
        drawCircle(
            color = colors.hairlineStrong,
            radius = headRadius,
            center = position,
            style = Stroke(width = 1f),
        )
        drawLine(
            color = colors.groove.copy(alpha = 0.8f),
            start = Offset(position.x - headRadius * 0.65f, position.y - headRadius * 0.65f),
            end = Offset(position.x + headRadius * 0.65f, position.y + headRadius * 0.65f),
            strokeWidth = 1f,
        )
    }
}
