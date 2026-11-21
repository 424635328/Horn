package com.industrial.autohorn.audio.dsp

import kotlin.math.abs
import kotlin.math.sin

/**
 * Immutable single-cycle table generated from a harmonic series.
 *
 * Tables are built once when the engine is constructed (never on the audio thread), then shared
 * by all detuned oscillators of a voice. Linear interpolation at 2k points is transparent for
 * the harmonic content used here and is far cheaper than dozens of `sin()` calls per sample.
 */
internal class HarmonicWavetable(
    harmonics: DoubleArray,
    tableSize: Int = DEFAULT_TABLE_SIZE,
) {
    private val size = tableSize
    private val samples = DoubleArray(tableSize + 1)

    init {
        require(harmonics.isNotEmpty()) { "harmonics must not be empty" }
        require(tableSize >= 256) { "tableSize is too small" }

        var peak = 0.0
        for (index in 0 until size) {
            val phase = TWO_PI * index / size
            var value = 0.0
            for (harmonic in harmonics.indices) {
                value += harmonics[harmonic] * sin(phase * (harmonic + 1))
            }
            samples[index] = value
            peak = maxOf(peak, abs(value))
        }
        val scale = if (peak > 0.0) 1.0 / peak else 1.0
        for (index in 0 until size) {
            samples[index] *= scale
        }
        samples[size] = samples[0]
    }

    fun sample(phase: Double): Double {
        val position = phase * size
        val index = position.toInt().coerceIn(0, size - 1)
        val fraction = position - index
        return samples[index] + (samples[index + 1] - samples[index]) * fraction
    }

    private companion object {
        const val DEFAULT_TABLE_SIZE = 2_048
    }
}
