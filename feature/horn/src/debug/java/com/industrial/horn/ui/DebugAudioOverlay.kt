package com.industrial.horn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.AudioTelemetry
import com.industrial.horn.ui.theme.AppleGlassSurface
import com.industrial.horn.ui.theme.AppleNumbers
import com.industrial.horn.ui.theme.HornCream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Debug-only DSP monitor (v3.6 stereo). The dual-trace oscilloscope consumes the real L/R
 * telemetry rings; it never synthesizes a decorative waveform and is compiled out of release.
 */
@Composable
fun DebugAudioOverlayHost(
    accent: Color,
    telemetry: AudioTelemetry,
    readWaveform: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
    readWaveformStereo: (FloatArray, FloatArray) -> Int = { _, _ -> 0 },
) {
    var expanded by remember { mutableStateOf(false) }

    if (!expanded) {
        TextButton(
            onClick = { expanded = true },
            modifier = modifier.testTag("debug_overlay_toggle"),
        ) {
            Text("DSP", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        return
    }

    val scratchLeft = remember { FloatArray(256) }
    val scratchRight = remember { FloatArray(256) }
    var samplesLeft by remember { mutableStateOf(FloatArray(256)) }
    var samplesRight by remember { mutableStateOf(FloatArray(256)) }
    LaunchedEffect(expanded) {
        while (isActive) {
            readWaveformStereo(scratchLeft, scratchRight)
            samplesLeft = scratchLeft.copyOf()
            samplesRight = scratchRight.copyOf()
            delay(33L)
        }
    }

    Box(
        modifier =
            modifier
                .width(300.dp)
                .background(Color(0xCC0B0F13), RoundedCornerShape(14.dp))
                .testTag("debug_audio_overlay"),
    ) {
        AppleGlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(14.dp),
            rimAccent = accent,
            frostAlpha = 0.16f,
            contentPadding = PaddingValues(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "DSP · ${telemetry.engineName}",
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { expanded = false }) {
                        Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    text =
                        "RMS L ${"%.3f".format(telemetry.rmsLeft)}  R ${"%.3f".format(telemetry.rmsRight)}  ·  " +
                            "ρ ${"%.2f".format(telemetry.stereoCorrelation)}",
                    style =
                        TextStyle(
                            color = Color(0xFFA8B0B6),
                            fontSize = 9.sp,
                            fontFeatureSettings = AppleNumbers.TABULAR,
                        ),
                )
                Text(
                    text =
                        "LAT ${"%.1f".format(telemetry.latencyMs)} ms  ·  " +
                            "XRUN ${telemetry.xrunCount}  ·  " +
                            "RENDER ${telemetry.renderTimeMicros} µs",
                    style =
                        TextStyle(
                            color = Color(0xFFA8B0B6),
                            fontSize = 9.sp,
                            fontFeatureSettings = AppleNumbers.TABULAR,
                        ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = telemetry.outputMode.displayName,
                        color = HornCream,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (telemetry.isPlaying) "PLAYING" else "IDLE",
                        color = Color(0xFFA8B0B6),
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(74.dp)
                            .background(Color(0xFF05080A), RoundedCornerShape(6.dp)),
                ) {
                    val middle = size.height / 2f
                    drawLine(
                        color = accent.copy(alpha = 0.25f),
                        start = Offset(0f, middle),
                        end = Offset(size.width, middle),
                        strokeWidth = 1f,
                    )
                    drawTrace(samplesLeft, accent, middle, scale = 0.88f)
                    drawTrace(samplesRight, accent.copy(alpha = 0.55f), middle, scale = 0.72f)
                }
            }
        }
    }
}

private fun DrawScope.drawTrace(
    samples: FloatArray,
    color: Color,
    middle: Float,
    scale: Float,
) {
    val path = Path()
    val count = samples.size.coerceAtLeast(2)
    for (index in 0 until count) {
        val x = index.toFloat() / (count - 1) * size.width
        val y = middle - samples[index].coerceIn(-1f, 1f) * middle * scale
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = color, style = Stroke(width = 1.3f))
}
