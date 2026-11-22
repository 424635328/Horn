package com.industrial.horn.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class DualToneHornSynthTest {
    @Test
    fun outputIsBoundedNonSilentAndNeverClips() {
        val synth = DualToneHornSynth(SAMPLE_RATE)
        val frames = SAMPLE_RATE * 3
        val stereo = FloatArray(2 * frames)
        synth.render(stereo, frames)
        val samples = DspMeasurement.monoFold(stereo, frames)

        val peak = samples.maxOf { abs(it) }
        assertTrue("Synth must produce audible output", peak > 0.25f)
        assertTrue("Output must stay below full scale, was $peak", peak <= 0.95f)
        assertTrue("No sample may reach PCM full scale", stereo.none { abs(it) >= 1f })
    }

    @Test
    fun spectrumContainsBothFundamentalsAndStrongHarmonics() {
        val synth = DualToneHornSynth(SAMPLE_RATE)
        val frames = SAMPLE_RATE
        val stereo = FloatArray(2 * frames)
        synth.render(stereo, frames)
        val samples = DspMeasurement.monoFold(stereo, frames)

        val lowFundamental = magnitude(samples, 400.0)
        val highFundamental = magnitude(samples, 500.0)
        val lowSecond = magnitude(samples, 800.0)
        val lowThird = magnitude(samples, 1_200.0)
        val highSecond = magnitude(samples, 1_000.0)

        assertTrue("400 Hz fundamental missing: $lowFundamental", lowFundamental > 0.04)
        assertTrue("500 Hz fundamental missing: $highFundamental", highFundamental > 0.04)
        assertTrue("Dual-tone balance is implausible", lowFundamental / highFundamental in 0.4..2.5)

        // A pure sine would fail these ratios. The harmonic stack is what makes it sound like a horn.
        assertTrue("2nd harmonic of 400 Hz is too weak: $lowSecond", lowSecond > lowFundamental * 0.12)
        assertTrue("3rd harmonic of 400 Hz is too weak: $lowThird", lowThird > lowFundamental * 0.06)
        assertTrue("2nd harmonic of 500 Hz is too weak: $highSecond", highSecond > highFundamental * 0.08)
    }

    @Test
    fun stereoIsImagedButKeepsTheMonoFoldIntact() {
        val synth = DualToneHornSynth(SAMPLE_RATE)
        val frames = SAMPLE_RATE
        val stereo = FloatArray(2 * frames)
        synth.render(stereo, frames)
        val left = FloatArray(frames) { stereo[2 * it] }
        val right = FloatArray(frames) { stereo[2 * it + 1] }

        // The two horn bodies are cross-panned, so the channels must differ (not L=R copy) ...
        assertTrue("Left and right are identical copies", left.zip(right).any { (l, r) -> l != r })
        // ... while pure amplitude panning keeps them strongly correlated (02 §5 C1).
        val correlation = AudioTelemetryBuffer.stereoCorrelation(left, right, frames)
        assertTrue("Channels must stay correlated for mono folding: $correlation", correlation > 0.5f)
    }

    @Test
    fun synthesisIsDeterministicAndResetRestoresInitialState() {
        val synth = DualToneHornSynth(SAMPLE_RATE)
        val first = FloatArray(9_600)
        val second = FloatArray(9_600)

        synth.render(first, first.size / 2)
        synth.reset()
        synth.render(second, second.size / 2)

        assertArrayEquals(first, second, 0f)
    }

    @Test
    fun renderLeavesTailOfBufferUntouched() {
        val synth = DualToneHornSynth(SAMPLE_RATE)
        val buffer = FloatArray(1_024) { 7f }
        synth.render(buffer, 256)

        for (index in 512 until buffer.size) {
            assertTrue("Tail sample at $index was modified", buffer[index] == 7f)
        }
    }

    private fun magnitude(
        samples: FloatArray,
        frequency: Double,
    ): Double {
        var real = 0.0
        var imaginary = 0.0
        val angularStep = 2.0 * PI * frequency / SAMPLE_RATE
        for (index in samples.indices) {
            val angle = angularStep * index
            real += samples[index] * cos(angle)
            imaginary -= samples[index] * sin(angle)
        }
        return 2.0 * hypot(real, imaginary) / samples.size
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
