package com.industrial.horn.ui.theme

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/**
 * One-time capability probes. Robolectric/headless JVMs may lack AGSL or RenderEffect; probing
 * with runCatching keeps every rendering tier crash-free instead of assuming an SDK number
 * guarantees the feature actually works.
 */
object GlassCapabilities {
    val agslAvailable: Boolean by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(RIM_SHADER) }.isSuccess
        } else {
            false
        }
    }

    val blurAvailable: Boolean by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                android.graphics.RenderEffect.createBlurEffect(
                    22f,
                    22f,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }.isSuccess
        } else {
            false
        }
    }

    /** Effective tier for the current process, honoring test overrides. */
    fun resolve(override: GlassRenderMode): GlassRenderMode =
        when (override) {
            GlassRenderMode.AGSL -> {
                if (agslAvailable) {
                    GlassRenderMode.AGSL
                } else if (blurAvailable) {
                    GlassRenderMode.BLUR
                } else {
                    GlassRenderMode.CANVAS
                }
            }

            GlassRenderMode.BLUR -> {
                if (blurAvailable) GlassRenderMode.BLUR else GlassRenderMode.CANVAS
            }

            GlassRenderMode.CANVAS -> {
                GlassRenderMode.CANVAS
            }
        }
}

/**
 * Real multi-layer frosted glass surface:
 *  1. a blurred re-draw of the ambient mesh layer behind the panel (true backdrop blur),
 *  2. a translucent frost fill with 1.5% deterministic micro-grain (anti-banding),
 *  3. a directional specular rim (1dp top-left bright, bottom-right soft), AGSL-refracted on 33+.
 *
 * The [backdrop] lambda receives the surface origin in root coordinates so the ambient mesh can
 * be re-drawn exactly where it lies behind this panel.
 */
@Composable
fun AppleGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppleRadii.Panel),
    rimAccent: Color = Color.White,
    frostAlpha: Float = 0.10f,
    backdropAlpha: Float = 1f,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    backdrop: (DrawScope.(Offset) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val mode = GlassCapabilities.resolve(LocalGlassRenderMode.current)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val grain = rememberGrainPoints()

    Box(
        modifier =
            modifier
                .clip(shape)
                .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        if (backdrop != null && mode != GlassRenderMode.CANVAS && GlassCapabilities.blurAvailable) {
            val blurEffect =
                remember(mode) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { createBackdropBlur() }.getOrNull()
                    } else {
                        null
                    }
                }
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            renderEffect = blurEffect
                            alpha = backdropAlpha
                        }.drawWithCache {
                            onDrawBehind { backdrop(origin) }
                        },
            )
        }

        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val paint = Paint().apply { isAntiAlias = true }
                        val grainPaint =
                            Paint().apply {
                                isAntiAlias = true
                                color = Color.White.copy(alpha = 0.015f).toArgb()
                                strokeWidth = 2f
                            }
                        val grainPoints = FloatArray(grain.size)
                        var grainIndex = 0
                        while (grainIndex < grain.size) {
                            grainPoints[grainIndex] = grain[grainIndex] * size.width
                            grainPoints[grainIndex + 1] = grain[grainIndex + 1] * size.height
                            grainIndex += 2
                        }
                        val shader: android.graphics.Shader? =
                            if (mode == GlassRenderMode.AGSL && GlassCapabilities.agslAvailable &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                runCatching {
                                    buildRimShader(size.width, size.height, rimAccent)
                                }.getOrNull()
                            } else {
                                null
                            }
                        onDrawBehind {
                            drawRect(color = Color.White.copy(alpha = frostAlpha))
                            drawRect(color = Color.Black.copy(alpha = frostAlpha * 0.55f))
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawPoints(grainPoints, grainPaint)
                            }
                            if (shader != null) {
                                paint.shader = shader
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawRect(
                                        0f,
                                        0f,
                                        size.width,
                                        size.height,
                                        paint,
                                    )
                                }
                                paint.shader = null
                            } else {
                                drawGradientRim(rimAccent)
                            }
                        }
                    },
        )

        // Content sizes the box; the frost/backdrop layers above match it.
        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Deterministic 1.5% micro-grain. Points are generated once with a fixed-seed LCG so Roborazzi
 * captures stay byte-stable across runs while 8-bit gradients stop banding.
 */
@Composable
private fun rememberGrainPoints(): FloatArray =
    remember {
        val points = FloatArray(GRAIN_POINTS * 2)
        var seed = 0x9E3779B9.toInt()
        for (index in 0 until GRAIN_POINTS * 2) {
            seed = seed * 1664525 + 1013904223
            points[index] = ((seed ushr 8) and 0xFFFF) / 65535f
        }
        points
    }

@RequiresApi(Build.VERSION_CODES.S)
private fun createBackdropBlur(): androidx.compose.ui.graphics.RenderEffect =
    android.graphics.RenderEffect
        .createBlurEffect(
            22f,
            22f,
            android.graphics.Shader.TileMode.CLAMP,
        ).asComposeRenderEffect()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildRimShader(
    width: Float,
    height: Float,
    accent: Color,
): android.graphics.Shader =
    RuntimeShader(RIM_SHADER).apply {
        setFloatUniform("uSize", width, height)
        val argb = accent.toArgb()
        setFloatUniform(
            "uAccent",
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )
    }

private fun DrawScope.drawGradientRim(accent: Color) {
    val inset = 1f
    drawRect(
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.10f),
                        accent.copy(alpha = 0.16f),
                    ),
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset),
            ),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(width = 1.dp.toPx()),
    )
}

private const val GRAIN_POINTS = 160

private const val RIM_SHADER = """
uniform float2 uSize;
uniform float3 uAccent;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uSize;
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    float rim = smoothstep(0.035, 0.0, edge);
    float diagonal = clamp((uv.x + uv.y) * 0.5, 0.0, 1.0);
    float glint = rim * mix(1.15, 0.25, diagonal);
    float3 tint = mix(float3(1.0), uAccent, 0.35);
    return half4(half3(tint * glint), half(glint * 0.55));
}
"""
