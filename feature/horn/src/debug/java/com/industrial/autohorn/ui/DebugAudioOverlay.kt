package com.industrial.autohorn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.autohorn.audio.AudioTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Debug-only DSP monitor. The oscilloscope consumes the real native/JVM telemetry ring; it never
 * synthesizes a decorative waveform and is compiled out of release.
 */
@Composable
fun DebugAudioOverlayHost(
    accent: Color,
    telemetry: AudioTelemetry,
    readWaveform: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
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

    val scratch = remember { FloatArray(256) }
    var samples by remember { mutableStateOf(FloatArray(256)) }
    LaunchedEffect(expanded) {
        while (isActive) {
            readWaveform(scratch)
            samples = scratch.copyOf()
            delay(33L)
        }
    }

    Surface(
        modifier = modifier
            .width(300.dp)
            .testTag("debug_audio_overlay"),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xEE0B0F13),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                text = "RMS ${"%.3f".format(telemetry.rms)}  ·  " +
                    "LAT ${"%.1f".format(telemetry.latencyMs)} ms  ·  " +
                    "XRUN ${telemetry.xrunCount}",
                color = Color(0xFFA8B0B6),
                fontSize = 9.sp,
            )
            Text(
                text = "RENDER ${telemetry.renderTimeMicros} µs  ·  " +
                    "PLAYING ${telemetry.isPlaying}",
                color = Color(0xFFA8B0B6),
                fontSize = 9.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(
                modifier = Modifier
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
                val path = Path()
                val count = samples.size.coerceAtLeast(2)
                for (index in 0 until count) {
                    val x = index.toFloat() / (count - 1) * size.width
                    val y = middle - samples[index].coerceIn(-1f, 1f) * middle * 0.88f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = accent,
                    style = Stroke(width = 1.3f),
                )
            }
        }
    }
}
