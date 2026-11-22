package com.industrial.horn.audio.dsp

/**
 * Phase accumulator over a [HarmonicWavetable]. Frequency can be scaled per sample for physical
 * pitch bends without reallocating or recalculating a table.
 */
internal class WavetableOscillator(
    frequencyHz: Double,
    sampleRate: Int,
    private val table: HarmonicWavetable,
    initialPhase: Double = 0.0,
) {
    init {
        require(frequencyHz > 0.0) { "frequency must be positive" }
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    private val phaseIncrement = frequencyHz / sampleRate
    private val startPhase = initialPhase % 1.0
    private var phase = startPhase

    fun nextSample(frequencyScale: Double = 1.0): Double {
        val value = table.sample(phase)
        phase += phaseIncrement * frequencyScale
        if (phase >= 1.0) phase -= 1.0
        return value
    }

    fun reset() {
        phase = startPhase
    }
}
