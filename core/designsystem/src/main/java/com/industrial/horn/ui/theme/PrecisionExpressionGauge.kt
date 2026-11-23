package com.industrial.horn.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 240° precision expression gauge: a real moving-coil scale, not a progress bar.
 *
 * Geometry is frozen (`docs/spec/03 §6.4`): zero at 12 o'clock, −100 % at 150°, +100 % at 30°, one
 * minor graduation per 10° and a major every 60°. The scale lives on a raised bezel ring between
 * the dial face and the pressure ticks, and the needle is direct-coupled to [expressionProvider] —
 * a finger-driven value must not lag behind the finger — read inside the draw lambda, so drag
 * updates invalidate drawing only.
 */
internal object GaugeGeometry {
    const val START_ANGLE = 150f
    const val SWEEP = 240f
    const val MINOR_STEP = 10
    const val MAJOR_EVERY = 6

    /** Bezel band that carries the scale, as fractions of the dial radius. */
    const val INNER = 0.862f
    const val OUTER = 0.925f

    fun angleOf(expression: Float): Float = START_ANGLE + (expression.coerceIn(-1f, 1f) + 1f) * 0.5f * SWEEP
}

@Composable
fun PrecisionExpressionGauge(
    expressionProvider: () -> Float,
    accent: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val colors = LocalHornColors.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .drawWithCache {
                    val radius = min(size.width, size.height) / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val inner = radius * GaugeGeometry.INNER
                    val outer = radius * GaugeGeometry.OUTER
                    val bandCenter = (inner + outer) * 0.5f
                    val bandWidth = outer - inner
                    val bandFill =
                        Brush.radialGradient(
                            colors = listOf(colors.bezelTop, colors.bezelBottom),
                            center = center,
                            radius = outer,
                        )
                    val tickPath = Path()
                    var degrees = GaugeGeometry.START_ANGLE
                    while (degrees <= GaugeGeometry.START_ANGLE + GaugeGeometry.SWEEP) {
                        val index = ((degrees - GaugeGeometry.START_ANGLE) / GaugeGeometry.MINOR_STEP).toInt()
                        val major = index % GaugeGeometry.MAJOR_EVERY == 0
                        val length = if (major) bandWidth * 0.78f else bandWidth * 0.40f
                        val rad = degrees * (PI.toFloat() / 180f)
                        tickPath.moveTo(
                            center.x + cos(rad) * (outer - length),
                            center.y + sin(rad) * (outer - length),
                        )
                        tickPath.lineTo(center.x + cos(rad) * outer, center.y + sin(rad) * outer)
                        degrees += GaugeGeometry.MINOR_STEP
                    }
                    onDrawBehind {
                        // Bezel band: a raised ring drawn as a thick stroke, so the dial face
                        // underneath stays visible and no "tyre" gap appears.
                        drawCircle(
                            brush = bandFill,
                            radius = bandCenter,
                            center = center,
                            style = Stroke(width = bandWidth),
                        )
                        drawCircle(
                            color = colors.hairlineStrong,
                            radius = outer,
                            center = center,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                        drawCircle(
                            color = colors.hairlineStrong,
                            radius = inner,
                            center = center,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                        // Forward half is the accent zone: a thin bright arc on the inner edge of
                        // the bezel, so it reads as a printed zone and never as a lit tick.
                        val zoneRadius = bandCenter - bandWidth * 0.26f
                        drawArc(
                            color = accent.copy(alpha = if (active) 0.85f else 0.35f),
                            startAngle = GaugeGeometry.START_ANGLE + GaugeGeometry.SWEEP * 0.5f,
                            sweepAngle = GaugeGeometry.SWEEP * 0.5f,
                            useCenter = false,
                            topLeft = Offset(center.x - zoneRadius, center.y - zoneRadius),
                            size = Size(zoneRadius * 2f, zoneRadius * 2f),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                        drawPath(
                            path = tickPath,
                            color = colors.textPrimary.copy(alpha = 0.62f),
                            style = Stroke(1.2.dp.toPx()),
                        )

                        val angle = GaugeGeometry.angleOf(expressionProvider())
                        val rad = angle * (PI.toFloat() / 180f)
                        // The tail runs under the opaque dial plate (radius 0.46R), so the
                        // visible needle is the segment between the plate edge and the scale.
                        val tip =
                            Offset(
                                center.x + cos(rad) * (radius * 0.905f),
                                center.y + sin(rad) * (radius * 0.905f),
                            )
                        val tail =
                            Offset(
                                center.x - cos(rad) * (radius * 0.20f),
                                center.y - sin(rad) * (radius * 0.20f),
                            )
                        // A hairline dark edge under the needle keeps it readable on the light
                        // ceramic face without changing the accent hue.
                        drawLine(
                            color = colors.dialBottom.copy(alpha = 0.55f),
                            start = tail,
                            end = tip,
                            strokeWidth = 4.dp.toPx(),
                        )
                        drawLine(
                            color = accent,
                            start = tail,
                            end = tip,
                            strokeWidth = 2.dp.toPx(),
                        )
                        drawCircle(
                            color = accent,
                            radius = 2.4.dp.toPx(),
                            center = tip,
                        )
                    }
                },
    )
}

/** Shared graduation drawing for the outer RMS pressure ring (60 ticks). */
internal fun DrawScope.drawPressureTicks(
    rms: Float,
    accent: Color,
    colors: HornColors,
    tickCount: Int = 60,
) {
    val radius = min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val outer = radius
    val innerBase = radius * 0.932f
    val lit = (rms.coerceIn(0f, 1f) * tickCount).toInt()
    val stepRad = (360f / tickCount) * (PI.toFloat() / 180f)
    for (index in 0 until tickCount) {
        val major = index % 5 == 0
        val length = if (major) (outer - innerBase) else (outer - innerBase) * 0.58f
        val angle = -90f * (PI.toFloat() / 180f) + index * stepRad
        val cosA = cos(angle)
        val sinA = sin(angle)
        val litHere = index < lit
        drawLine(
            color =
                if (litHere) {
                    accent
                } else {
                    colors.textTertiary.copy(alpha = if (colors.isDark) 0.62f else 0.55f)
                },
            start = Offset(center.x + cosA * (outer - length), center.y + sinA * (outer - length)),
            end = Offset(center.x + cosA * outer, center.y + sinA * outer),
            strokeWidth =
                if (litHere) {
                    2.dp.toPx()
                } else if (major) {
                    1.6.dp.toPx()
                } else {
                    1.dp.toPx()
                },
        )
    }
}
