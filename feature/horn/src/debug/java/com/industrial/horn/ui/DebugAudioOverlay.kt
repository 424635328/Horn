package com.industrial.horn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.industrial.horn.audio.AudioTelemetry
import com.industrial.horn.ui.theme.HornRadii
import com.industrial.horn.ui.theme.HornText
import com.industrial.horn.ui.theme.LocalHornColors
import com.industrial.horn.ui.theme.MachinedPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Debug-only DSP monitor (v3.6 stereo, v4.0 cockpit chrome).
 *
 * A fluorescent dual-trace scope: left channel in phosphor green, right channel in instrument
 * amber, on a dark green-black graticule. The traces consume the real L/R telemetry rings — the
 * widget never synthesizes a decorative waveform — and the whole file is compiled out of release.
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
    val colors = LocalHornColors.current

    if (!expanded) {
        Box(
            modifier =
                modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(HornRadii.Key))
                    .background(colors.panel)
                    .border(1.dp, colors.hairlineStrong, RoundedCornerShape(HornRadii.Key))
                    .clickable { expanded = true }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Open DSP monitor"
                    }.testTag("debug_overlay_toggle"),
            contentAlignment = Alignment.Center,
        ) {
            Text("DSP", color = accent, style = HornText.Data)
        }
        return
    }

    val scratchLeft = remember { FloatArray(256) }
    val scratchRight = remember { FloatArray(256) }
    var samplesLeft by remember { mutableStateOf(FloatArray(256)) }
    var samplesRight by remember { mutableStateOf(FloatArray(256)) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded) {
        while (isActive) {
            readWaveformStereo(scratchLeft, scratchRight)
            samplesLeft = scratchLeft.copyOf()
            samplesRight = scratchRight.copyOf()
            frame++
            delay(33L)
        }
    }

    MachinedPanel(
        modifier = modifier.width(300.dp).testTag("debug_audio_overlay"),
        accent = accent,
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DSP · ${telemetry.engineName}",
                color = accent,
                style = HornText.Data,
            )
            Box(
                modifier =
                    Modifier
                        .size(22.dp)
                        .clickable { expanded = false }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Close DSP monitor"
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = colors.textTertiary, style = HornText.DataLarge)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text =
                "L ${"%.3f".format(telemetry.rmsLeft)}  R ${"%.3f".format(telemetry.rmsRight)}  " +
                    "ρ ${"%.2f".format(telemetry.stereoCorrelation)}",
            color = colors.textSecondary,
            style = HornText.Data,
        )
        Text(
            text =
                "LAT ${"%.1f".format(telemetry.latencyMs)}ms  XRUN ${telemetry.xrunCount}  " +
                    "RND ${telemetry.renderTimeMicros}µs",
            color = colors.textSecondary,
            style = HornText.Data,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = telemetry.outputMode.displayName,
                color = colors.textPrimary,
                style = HornText.PlateLabel,
            )
            Text(
                text = if (telemetry.isPlaying) "PLAYING" else "IDLE",
                color = if (telemetry.isPlaying) TraceLeft else colors.textTertiary,
                style = HornText.PlateLabel,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(HornRadii.Well))
                    .background(ScopeBackground)
                    .border(1.dp, ScopeGraticule, RoundedCornerShape(HornRadii.Well)),
        ) {
            drawGraticule()
            drawTrace(samplesLeft, TraceLeft, scale = 0.86f)
            drawTrace(samplesRight, TraceRight, scale = 0.70f)
            // Frame counter: proves the traces are live, not a frozen static image.
            drawRect(
                color = TraceLeft.copy(alpha = 0.5f),
                topLeft = Offset(2f, 2f),
                size =
                    androidx.compose.ui.geometry
                        .Size((frame % 12) * 1.5f + 2f, 2f),
            )
        }
    }
}

private fun DrawScope.drawGraticule() {
    val middle = size.height / 2f
    drawLine(
        color = ScopeGraticule,
        start = Offset(0f, middle),
        end = Offset(size.width, middle),
        strokeWidth = 1f,
    )
    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = ScopeGraticule.copy(alpha = 0.45f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
        x += size.width / 8f
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(
            color = ScopeGraticule.copy(alpha = 0.45f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += size.height / 4f
    }
}

/** Two-pass phosphor trace: a wide low-alpha halo under a crisp 1 px beam. */
private fun DrawScope.drawTrace(
    samples: FloatArray,
    color: Color,
    scale: Float,
) {
    val middle = size.height / 2f
    val count = samples.size.coerceAtLeast(2)
    val path = Path()
    for (index in 0 until count) {
        val x = index.toFloat() / (count - 1) * size.width
        val y = middle - samples[index].coerceIn(-1f, 1f) * middle * scale
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = color.copy(alpha = 0.18f), style = Stroke(width = 4f))
    drawPath(path = path, color = color, style = Stroke(width = 1.2f))
}

private val ScopeBackground = Color(0xFF06120C)
private val ScopeGraticule = Color(0xFF1D3B2B)
private val TraceLeft = Color(0xFF4DFFC4)
private val TraceRight = Color(0xFFFFB547)
