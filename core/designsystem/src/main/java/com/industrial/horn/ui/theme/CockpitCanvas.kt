package com.industrial.horn.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The cockpit substrate: an engineering-grid ceramic/carbon surface with engraved registration
 * marks. It replaces the blurred ambient mesh of the previous generation — there is no backdrop
 * blur, no translucency, and no per-frame work while the instrument is idle.
 *
 * While honking, one accent bloom and one slow scan line are the only moving elements. Both read
 * live state inside the draw lambda: the scan phase is a [State] read only by the draw node (not
 * by composition), and the RMS envelope arrives as a provider, so audio-rate updates invalidate
 * drawing alone — never the draw cache, never composition.
 */
@Composable
fun CockpitCanvas(
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    rmsProvider: () -> Float = { 0f },
) {
    val colors = LocalHornColors.current
    val reducedMotion = LocalReducedMotion.current
    val animate = active && !reducedMotion && !LocalInspectionMode.current
    val scanPhase = rememberScanPhase(animate)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .drawWithCache {
                    val minor = 16.dp.toPx()
                    val major = minor * 5f
                    val gridPath = Path()
                    var x = 0f
                    while (x <= size.width) {
                        gridPath.moveTo(x, 0f)
                        gridPath.lineTo(x, size.height)
                        x += minor
                    }
                    var y = 0f
                    while (y <= size.height) {
                        gridPath.moveTo(0f, y)
                        gridPath.lineTo(size.width, y)
                        y += minor
                    }
                    val majorPath = Path()
                    x = 0f
                    while (x <= size.width) {
                        majorPath.moveTo(x, 0f)
                        majorPath.lineTo(x, size.height)
                        x += major
                    }
                    y = 0f
                    while (y <= size.height) {
                        majorPath.moveTo(0f, y)
                        majorPath.lineTo(size.width, y)
                        y += major
                    }
                    val vignette =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    colors.groove.copy(alpha = if (colors.isDark) 0.45f else 0.20f),
                                ),
                            center = Offset(size.width / 2f, size.height * 0.46f),
                            radius = maxOf(size.width, size.height) * 0.78f,
                        )
                    onDrawBehind {
                        drawRect(color = colors.canvas)
                        drawPath(gridPath, color = colors.grid, alpha = 0.55f, style = Stroke(1f))
                        drawPath(majorPath, color = colors.grid, alpha = 0.95f, style = Stroke(1f))
                        drawRect(brush = vignette)
                        drawRegistrationMarks(colors)

                        val rms = rmsProvider().coerceIn(0f, 1f)
                        if (active) {
                            drawPressureBloom(
                                accent = accent,
                                rms = rms,
                                center = Offset(size.width / 2f, size.height * 0.42f),
                            )
                            val scan = scanPhase.value
                            if (scan > 0f) {
                                drawScanBand(accent = accent, rms = rms, scan = scan)
                            }
                        }
                    }
                },
    )
}

/**
 * Scan phase as a draw-only [State]. Returning the State (instead of a delegated Float) keeps the
 * animation value out of composition, so `drawWithCache` builds its grid paths exactly once per
 * size while the band still animates at frame rate.
 */
@Composable
private fun rememberScanPhase(animate: Boolean): State<Float> =
    if (animate) {
        rememberInfiniteTransition(label = "cockpitScan").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing)),
            label = "cockpitScanPhase",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

/** Four caliper registration marks: the visual signature of a machined reference plane. */
private fun DrawScope.drawRegistrationMarks(colors: HornColors) {
    val inset = 14.dp.toPx()
    val arm = 9.dp.toPx()
    val stroke = 1.dp.toPx()
    val marks =
        listOf(
            Offset(inset, inset) to (1f to 1f),
            Offset(size.width - inset, inset) to (-1f to 1f),
            Offset(inset, size.height - inset) to (1f to -1f),
            Offset(size.width - inset, size.height - inset) to (-1f to -1f),
        )
    marks.forEach { (origin, direction) ->
        val (dx, dy) = direction
        drawLine(
            color = colors.hairlineStrong.copy(alpha = 0.7f),
            start = origin,
            end = Offset(origin.x + arm * dx, origin.y),
            strokeWidth = stroke,
        )
        drawLine(
            color = colors.hairlineStrong.copy(alpha = 0.7f),
            start = origin,
            end = Offset(origin.x, origin.y + arm * dy),
            strokeWidth = stroke,
        )
    }
}

private fun DrawScope.drawPressureBloom(
    accent: Color,
    rms: Float,
    center: Offset,
) {
    val radius = min(size.width, size.height) * 0.62f
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        accent.copy(alpha = 0.10f + rms * 0.10f),
                        accent.copy(alpha = 0.03f + rms * 0.03f),
                        Color.Transparent,
                    ),
                center = center,
                radius = radius,
            ),
        radius = radius,
        center = center,
    )
}

private fun DrawScope.drawScanBand(
    accent: Color,
    rms: Float,
    scan: Float,
) {
    val band = min(size.width, size.height) * 0.5f
    val top = size.height * scan - band
    drawRect(
        brush =
            Brush.verticalGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        accent.copy(alpha = 0.04f + rms * 0.05f),
                        Color.Transparent,
                    ),
                startY = top,
                endY = top + band,
            ),
    )
}
