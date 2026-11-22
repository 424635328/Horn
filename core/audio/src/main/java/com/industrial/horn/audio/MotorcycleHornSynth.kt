package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.HarmonicWavetable
import com.industrial.horn.audio.dsp.WavetableOscillator
import com.industrial.horn.audio.dsp.softSaturate
import kotlin.math.PI
import kotlin.math.sin

/**
 * Compact single-tone motorcycle disc horn.
 *
 * A 444 Hz metal diaphragm with strong odd/even harmonics is amplitude-modulated at 29 Hz to
 * imitate the mechanical flutter of a vibrating disc. A 190 Hz high-pass removes the heavy
 * sub-bass of the car/train voices and a 4.8 kHz low-pass keeps the sound focused and dry.
 */
class MotorcycleHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : HornSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val table = HarmonicWavetable(DIAPHRAGM_HARMONICS)
    private val oscillator = WavetableOscillator(444.0, sampleRate, table, 0.13)
    private val highPass = BiquadFilter.highPass(sampleRate, 190.0, 0.707)
    private val lowPass = BiquadFilter.lowPass(sampleRate, 4_800.0, 0.707)
    private val modulationIncrement = 2.0 * PI * FLUTTER_HZ / sampleRate
    private val expression = ExpressionModulator()

    private var modulationPhase = 0.0

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
            modulationPhase += modulationIncrement
            if (modulationPhase >= 2.0 * PI) modulationPhase -= 2.0 * PI
            val flutter = 1.0 + FLUTTER_DEPTH * sin(modulationPhase)

            val mixed = oscillator.nextSample(expression.pitchScale) * flutter
            val saturated = softSaturate(mixed, DRIVE * expression.driveScale)
            val focused = lowPass.process(highPass.process(saturated))

            buffer[index] =
                (focused * OUTPUT_GAIN)
                    .toFloat()
                    .coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
        }
    }

    override fun reset() {
        oscillator.reset()
        highPass.reset()
        lowPass.reset()
        modulationPhase = 0.0
        expression.reset()
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val FLUTTER_HZ = 29.0
        const val FLUTTER_DEPTH = 0.18
        const val DRIVE = 2.0
        const val OUTPUT_GAIN = 0.88
        const val MAX_OUTPUT = 0.95f

        val DIAPHRAGM_HARMONICS =
            doubleArrayOf(1.0, 0.50, 0.36, 0.25, 0.17, 0.11, 0.07, 0.045)
    }
}
