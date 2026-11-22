package com.industrial.horn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTelemetryBufferTest {
    @Test
    fun telemetryPublishesRmsLatencyXrunsAndEngine() {
        val buffer = AudioTelemetryBuffer("Oboe C++")
        buffer.update(
            rms = 0.42f,
            latencyMs = 8.5f,
            xrunCount = 3,
            renderMicros = 77,
            isPlaying = true,
        )

        val snapshot = buffer.snapshot()
        assertEquals("Oboe C++", snapshot.engineName)
        assertEquals(0.42f, snapshot.rms, 0.0001f)
        assertEquals(8.5f, snapshot.latencyMs, 0.0001f)
        assertEquals(3L, snapshot.xrunCount)
        assertEquals(77, snapshot.renderTimeMicros)
        assertTrue(snapshot.isPlaying)
    }

    @Test
    fun waveformRingReturnsChronologicalNewestSamples() {
        val buffer = AudioTelemetryBuffer("AudioTrack JVM", waveformCapacity = 256)
        val pcm = ShortArray(256) { (it * 100).toShort() }
        buffer.pushWaveform(pcm, pcm.size)

        val output = FloatArray(256)
        assertEquals(256, buffer.readWaveform(output))
        for (index in 0 until 256) {
            assertEquals(pcm[index] / 32_768f, output[index], 0.0001f)
        }
    }

    @Test
    fun pcmRmsIsFiniteAndBounded() {
        val buffer = AudioTelemetryBuffer("AudioTrack JVM")
        val pcm = ShortArray(240) { Short.MAX_VALUE }
        val rms = buffer.calculateRms(pcm, pcm.size)
        assertTrue(rms.isFinite())
        assertTrue(rms in 0.9f..1f)
    }

    @Test
    fun stereoUpdatePublishesPerChannelLevelsCorrelationAndMode() {
        val buffer = AudioTelemetryBuffer("Oboe C++")
        buffer.updateStereo(
            rmsLeft = 0.3f,
            rmsRight = 0.5f,
            correlation = -0.25f,
            outputMode = OutputMode.HEADPHONE_FULL_RANGE,
            latencyMs = 9f,
            xrunCount = 2,
            renderMicros = 88,
            isPlaying = true,
        )

        val snapshot = buffer.snapshot()
        assertEquals(0.3f, snapshot.rmsLeft, 0.0001f)
        assertEquals(0.5f, snapshot.rmsRight, 0.0001f)
        assertEquals(0.5f, snapshot.rms, 0.0001f)
        assertEquals(-0.25f, snapshot.stereoCorrelation, 0.0001f)
        assertEquals(OutputMode.HEADPHONE_FULL_RANGE, snapshot.outputMode)
        assertTrue(snapshot.isPlaying)
    }

    @Test
    fun stereoWaveformRingsAreIndependentAndChronological() {
        val buffer = AudioTelemetryBuffer("AudioTrack JVM", waveformCapacity = 256)
        val left = ShortArray(256) { (it * 100).toShort() }
        val right = ShortArray(256) { (-it * 50).toShort() }
        buffer.pushWaveformStereo(left, right, 256)

        val outL = FloatArray(256)
        val outR = FloatArray(256)
        assertEquals(256, buffer.readWaveformStereo(outL, outR))
        for (index in 0 until 256) {
            assertEquals(left[index] / 32_768f, outL[index], 0.0001f)
            assertEquals(right[index] / 32_768f, outR[index], 0.0001f)
        }
    }

    @Test
    fun stereoCorrelationMatchesKnownSignals() {
        val frames = 128
        val mono = FloatArray(frames) { kotlin.math.sin(it * 0.1).toFloat() }
        val inverted = FloatArray(frames) { -mono[it] }

        assertEquals(
            "Identical channels are fully correlated",
            1f,
            AudioTelemetryBuffer.stereoCorrelation(mono, mono, frames),
            0.0001f,
        )
        assertEquals(
            "Anti-phase channels correlate to -1",
            -1f,
            AudioTelemetryBuffer.stereoCorrelation(mono, inverted, frames),
            0.0001f,
        )
        assertEquals(
            "Silent blocks must not look anti-phase",
            1f,
            AudioTelemetryBuffer.stereoCorrelation(FloatArray(frames), FloatArray(frames), frames),
            0.0001f,
        )

        // Deterministic pseudo-independent pair: L = signal, R = delayed signal.
        val delayed = FloatArray(frames) { if (it == 0) 0f else mono[it - 1] }
        val decorrelated = AudioTelemetryBuffer.stereoCorrelation(mono, delayed, frames)
        assertTrue("Delayed pair should stay below perfect correlation", decorrelated < 0.999f)
        assertTrue("Correlation stays bounded", decorrelated in -1f..1f)
    }
}
