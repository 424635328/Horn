package com.industrial.horn.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared parameters for the breathing ambient mesh. Providers are read lazily inside draw
 * lambdas (deferred state reads) so RMS and phase updates redraw without recomposition.
 */
@Immutable
data class AmbientMeshSpec(
    val palette: AmbientPalette,
    val dark: Boolean,
    val phaseProvider: () -> Float,
    val rmsProvider: () -> Float,
    val honking: Boolean,
)

/**
 * Draws the slow multi-blob mesh field. [origin] shifts the field so glass panels can re-draw the
 * exact same layer underneath their frost for a true backdrop blur.
 */
fun DrawScope.drawAmbientMesh(
    spec: AmbientMeshSpec,
    origin: Offset = Offset.Zero,
) {
    val phase = spec.phaseProvider()
    val rms = spec.rmsProvider().coerceIn(0f, 1f)
    val base = if (spec.dark) DarkBackground else LightBackground
    val surface = if (spec.dark) DarkSurface else LightSurface
    val outline = if (spec.dark) DarkOnSurfaceVariant else LightOnSurfaceVariant

    translate(left = -origin.x, top = -origin.y) {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(base, surface.copy(alpha = 0.72f), base),
                ),
        )

        val pressure = if (spec.honking) 1f + rms * 0.45f else 1f
        val palette = spec.palette
        val tints =
            listOf(
                palette.tint(spec.dark),
                palette.meshSoft,
                palette.tint(spec.dark),
            )

        // Three soft blobs drifting on offset orbits; honking pressure expands their radii.
        repeat(3) { index ->
            val orbit = phase * 6.28318f + index * 2.094f
            val center =
                Offset(
                    x = size.width * (0.28f + 0.22f * index) + sin(orbit) * size.width * 0.09f,
                    y = size.height * (0.30f + 0.22f * index) + cos(orbit * 0.7f) * size.height * 0.07f,
                )
            val radius = min(size.width, size.height) * (0.34f + 0.10f * index) * pressure
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                tints[index].copy(alpha = 0.13f * pressure),
                                tints[index].copy(alpha = 0.035f),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = radius,
                    ),
                radius = radius,
                center = center,
            )
        }

        val gridStep = 56f * density
        var x = 0f
        while (x < size.width) {
            drawLine(outline.copy(alpha = 0.045f), Offset(x, 0f), Offset(x, size.height), 1f)
            x += gridStep
        }
        var y = 0f
        while (y < size.height) {
            drawLine(outline.copy(alpha = 0.04f), Offset(0f, y), Offset(size.width, y), 1f)
            y += gridStep
        }

        val center = Offset(size.width / 2f, size.height * 0.49f)
        val ringBase = min(size.width, size.height) * 0.22f
        repeat(4) { index ->
            drawCircle(
                color = outline.copy(alpha = 0.055f - index * 0.008f),
                radius = ringBase * (index + 1),
                center = center,
                style = Stroke(width = 1f),
            )
        }
    }
}

/**
 * Breathing ambient mesh background. The infinite phase animation is lifecycle aware: it stops
 * when the host leaves the STARTED state, so background rendering never burns battery.
 */
@Composable
fun AmbientMeshBackground(
    spec: AmbientMeshSpec,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val resumed = remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> resumed.value = true
                    else -> resumed.value = false
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            resumed.value = false
        }
    }

    val reduceMotion = LocalReducedMotion.current
    val phaseState: State<Float> =
        if (resumed.value && !reduceMotion) {
            val transition = rememberInfiniteTransition(label = "ambientMesh")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(26_000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "ambientMeshPhase",
            )
        } else {
            remember { mutableStateOf(0.15f) }
        }

    val animatedSpec =
        remember(spec, phaseState) {
            spec.copy(phaseProvider = { phaseState.value })
        }
    Canvas(modifier = modifier) { drawAmbientMesh(animatedSpec) }
}
