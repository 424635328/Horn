package com.industrial.autohorn.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.industrial.autohorn.audio.AudioTelemetry

/** Release builds expose no debug surface at all. */
@Composable
fun DebugAudioOverlayHost(
    accent: Color,
    telemetry: AudioTelemetry,
    readWaveform: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
) {
    // Intentionally empty: debug instrumentation must not ship.
}
