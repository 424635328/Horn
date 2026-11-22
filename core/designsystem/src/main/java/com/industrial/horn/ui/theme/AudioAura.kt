package com.industrial.horn.ui.theme

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Audio-pressure aura. API 33+ uses an AGSL RuntimeShader for the metallic light field; older
 * devices use the same geometry through a high-quality Compose Canvas fallback. The RMS level is
 * read lazily through [rmsProvider] inside draw lambdas so envelope updates redraw without
 * recomposition.
 */
@Composable
fun AudioAura(
    accent: Color,
    rmsProvider: () -> Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!active) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslAura(accent = accent, rmsProvider = rmsProvider, modifier = modifier)
    } else {
        CanvasAura(accent = accent, rmsProvider = rmsProvider, modifier = modifier)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslAura(
    accent: Color,
    rmsProvider: () -> Float,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(AURA_SHADER) }
    val paint =
        remember {
            Paint().apply {
                isAntiAlias = true
                isDither = true
            }
        }
    val transition = rememberInfiniteTransition(label = "agslAura")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "agslTime",
    )

    Canvas(modifier = modifier) {
        val rms = rmsProvider().coerceIn(0f, 1f)
        shader.setFloatUniform("uResolution", size.width, size.height)
        shader.setFloatUniform("uTime", time * 6.28318f)
        shader.setFloatUniform("uRms", rms)
        shader.setFloatUniform("uAccent", accent.red, accent.green, accent.blue)
        paint.shader = shader
        paint.alpha = (90 + rms * 165).toInt()
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawCircle(
                size.width / 2f,
                size.height / 2f,
                min(size.width, size.height) / 2f,
                paint,
            )
        }
    }
}

@Composable
private fun CanvasAura(
    accent: Color,
    rmsProvider: () -> Float,
    modifier: Modifier,
) {
    val transition = rememberInfiniteTransition(label = "canvasAura")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "canvasAuraPhase",
    )
    Canvas(modifier = modifier) {
        val rms = rmsProvider().coerceIn(0f, 1f)
        val center = Offset(size.width / 2f, size.height / 2f)
        val strength = 0.18f + rms * 0.55f
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            accent.copy(alpha = 0.16f * strength),
                            accent.copy(alpha = 0.04f * strength),
                            Color.Transparent,
                        ),
                    center = center,
                    radius = size.minDimension * 0.55f,
                ),
            radius = size.minDimension * 0.55f,
            center = center,
        )
        repeat(3) { index ->
            val wave = (phase + index / 3f) % 1f
            drawCircle(
                color = accent.copy(alpha = (1f - wave) * 0.22f * strength),
                radius = size.minDimension * (0.28f + wave * 0.25f),
                center = center,
                style = Stroke(width = (1.4f - wave * 0.5f).dp.toPx()),
            )
        }
    }
}

private const val AURA_SHADER = """
uniform float2 uResolution;
uniform float uTime;
uniform float uRms;
uniform float3 uAccent;

half4 main(float2 fragCoord) {
    float2 center = uResolution * 0.5;
    float2 p = fragCoord - center;
    float radius = max(uResolution.x, uResolution.y) * 0.5;
    float d = length(p) / radius;
    float wave = sin(d * 16.0 - uTime * 1.7) * 0.5 + 0.5;
    float pressure = uRms * 0.8 + 0.2;
    float glow = exp(-d * 5.5) * wave * pressure;
    float ring = smoothstep(0.02, 0.0, abs(d - 0.72)) * pressure;
    float alpha = clamp(glow * 0.65 + ring * 0.22, 0.0, 0.55);
    return half4(half3(uAccent) * (glow + ring * 1.4), alpha);
}
"""
