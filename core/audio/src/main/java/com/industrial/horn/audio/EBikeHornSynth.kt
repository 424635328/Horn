package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.HarmonicWavetable
import com.industrial.horn.audio.dsp.WavetableOscillator
import com.industrial.horn.audio.dsp.softSaturate

/**
 * City e-bike electronic horn.
 *
 * Two closely spaced 2.7 kHz pulse-wave emitters create the characteristic metallic chirp;
 * a low 685 Hz body adds weight without making it a car horn. A 500 Hz high-pass and 5.2 kHz
 * low-pass keep the spectrum in the piercing but non-fizzy band. The 4/6 ms envelope comes from
 * the voice profile, so presses feel immediate and taps remain crisp.
 */
class EBikeHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : HornSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val pulseTable = HarmonicWavetable(PULSE_HARMONICS)
    private val bodyTable = HarmonicWavetable(BODY_HARMONICS)
    private val highA = WavetableOscillator(2_740.0, sampleRate, pulseTable, 0.07)
    private val highB = WavetableOscillator(2_785.0, sampleRate, pulseTable, 0.53)
    private val body = WavetableOscillator(685.0, sampleRate, bodyTable, 0.21)
    private val highPass = BiquadFilter.highPass(sampleRate, 500.0, 0.707)
    private val lowPass = BiquadFilter.lowPass(sampleRate, 4_200.0, 0.707)
    private val expression = ExpressionModulator()

    override fun setExpression(expression: Float) {
        this.expression.setTarget(expression)
    }

    override fun render(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        require(frameCount in 0..buffer.size) { "frameCount outside buffer" }

        for (index in 0 until frameCount) {
            expression.advance()
            val pitch = expression.pitchScale
            val mixed =
                highA.nextSample(pitch) * 0.50 +
                    highB.nextSample(pitch) * 0.32 +
                    body.nextSample(pitch) * 0.13
            val saturated = softSaturate(mixed, DRIVE * expression.driveScale)
            val shaped = lowPass.process(highPass.process(saturated))
            buffer[index] =
                (shaped * OUTPUT_GAIN)
                    .toFloat()
                    .coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
        }
    }

    override fun reset() {
        highA.reset()
        highB.reset()
        body.reset()
        highPass.reset()
        lowPass.reset()
        expression.reset()
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DRIVE = 1.85
        const val OUTPUT_GAIN = 0.88
        const val MAX_OUTPUT = 0.95f

        // Rich, slightly asymmetric pulse spectrum; the wavetable normalizes its peak.
        val PULSE_HARMONICS = doubleArrayOf(1.0, 0.42, 0.46, 0.22, 0.19, 0.10, 0.08, 0.05)
        val BODY_HARMONICS = doubleArrayOf(1.0, 0.28, 0.12)
    }
}
