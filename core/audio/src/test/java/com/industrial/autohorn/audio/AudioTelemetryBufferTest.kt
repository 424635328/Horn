package com.industrial.autohorn.audio

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
}
