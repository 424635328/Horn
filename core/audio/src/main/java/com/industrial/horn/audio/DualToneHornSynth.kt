package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.softSaturate
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Real-time model of a pair of automotive snail horns.
 *
 * The acoustic model is intentionally not a pair of clean sine waves:
 *  - two fundamentals at 400 Hz and 500 Hz (the classic major-third dual horn),
 *  - a decaying harmonic stack on each tone, including even harmonics that create "body",
 *  - a soft tanh saturation stage for the nonlinear brass/plastic horn character,
 *  - a deterministic low-level noise bed for air turbulence,
 *  - a one-pole low-pass at 6 kHz to remove fizzy aliasing-like content,
 *  - a small 5.5 Hz amplitude sway to avoid a synthetic, static oscillator sound.
 *
 * The output is peak-limited below full scale so later gain staging cannot clip.
 */
class DualToneHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : HornSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val lowTone =
        ToneGenerator(
            frequency = 400.0,
            sampleRate = sampleRate,
            harmonicGains = LOW_HARMONICS,
        )
    private val highTone =
        ToneGenerator(
            frequency = 500.0,
            sampleRate = sampleRate,
            harmonicGains = HIGH_HARMONICS,
        )

    private val lowPassCoefficient = 1.0 - exp(-TWO_PI * LOW_PASS_HZ / sampleRate)
    private val swayIncrement = TWO_PI * SWAY_HZ / sampleRate

    private var lowPassState = 0.0
    private var noiseState = 0x4D595DF4
    private var noiseLowPass = 0.0
    private var swayPhase = 0.0
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
            val low = lowTone.nextSample(expression.pitchScale)
            val high = highTone.nextSample(expression.pitchScale)

            noiseState = noiseState * 1664525 + 1013904223
            val whiteNoise = ((noiseState ushr 8) and 0xFFFF) / 32768.0 - 1.0
            noiseLowPass += NOISE_SMOOTHING * (whiteNoise - noiseLowPass)

            swayPhase += swayIncrement
            if (swayPhase >= TWO_PI) swayPhase -= TWO_PI
            val sway = 1.0 + SWAY_DEPTH * sin(swayPhase)

            val mixed =
                (
                    low * LOW_WEIGHT +
                        high * HIGH_WEIGHT +
                        noiseLowPass * NOISE_LEVEL * expression.noiseScale
                ) * sway
            val saturated = softSaturate(mixed, DRIVE * expression.driveScale)
            lowPassState += lowPassCoefficient * (saturated - lowPassState)

            buffer[index] = (lowPassState * OUTPUT_GAIN).toFloat().coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
        }
    }

    override fun reset() {
        lowTone.reset()
        highTone.reset()
        lowPassState = 0.0
        noiseState = 0x4D595DF4
        noiseLowPass = 0.0
        swayPhase = 0.0
        expression.reset()
    }

    private class ToneGenerator(
        frequency: Double,
        sampleRate: Int,
        private val harmonicGains: DoubleArray,
    ) {
        private val phaseIncrement = TWO_PI * frequency / sampleRate
        private var phase = 0.0

        fun nextSample(frequencyScale: Double = 1.0): Double {
            var sample = 0.0
            for (harmonic in harmonicGains.indices) {
                sample += harmonicGains[harmonic] * sin(phase * (harmonic + 1))
            }
            phase += phaseIncrement * frequencyScale
            if (phase >= TWO_PI) phase -= TWO_PI
            return sample * HARMONIC_NORMALIZATION
        }

        fun reset() {
            phase = 0.0
        }
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val LOW_PASS_HZ = 6_000.0
        const val SWAY_HZ = 5.5
        const val SWAY_DEPTH = 0.018
        const val NOISE_LEVEL = 0.0055
        const val NOISE_SMOOTHING = 0.35
        const val DRIVE = 1.55
        const val OUTPUT_GAIN = 0.82
        const val MAX_OUTPUT = 0.95f
        const val LOW_WEIGHT = 0.52
        const val HIGH_WEIGHT = 0.48
        const val HARMONIC_NORMALIZATION = 0.43
        const val TWO_PI = 2.0 * PI
        val LOW_HARMONICS = doubleArrayOf(1.0, 0.62, 0.44, 0.30, 0.20, 0.14, 0.10, 0.07)
        val HIGH_HARMONICS = doubleArrayOf(1.0, 0.55, 0.42, 0.28, 0.18, 0.12, 0.08)
    }
}
