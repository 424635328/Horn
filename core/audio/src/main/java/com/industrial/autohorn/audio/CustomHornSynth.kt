package com.industrial.autohorn.audio

import com.industrial.autohorn.audio.dsp.BiquadFilter
import com.industrial.autohorn.audio.dsp.ExpressionModulator
import com.industrial.autohorn.audio.dsp.HarmonicWavetable
import com.industrial.autohorn.audio.dsp.WavetableOscillator
import com.industrial.autohorn.audio.dsp.softSaturate
import kotlin.math.pow

/**
 * Pro Sound Lab voice. The profile is sanitized once on construction; changing a slider creates
 * a new voice at the next idle boundary rather than mutating oscillator state mid-block.
 */
class CustomHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    rawProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
) : HornSynthesizer {
    private val profile = rawProfile.sanitized()
    private val harmonicCount = 12
    private val harmonics =
        DoubleArray(harmonicCount) { index ->
            if (index == 0) {
                1.0
            } else {
                (1.0 / (index + 1).toDouble().pow(1.45)) *
                    (0.22 + 0.78 * profile.harmonicRichness)
            }
        }
    private val table = HarmonicWavetable(harmonics)
    private val primary =
        WavetableOscillator(
            frequencyHz = profile.primaryFrequencyHz.toDouble(),
            sampleRate = sampleRate,
            table = table,
            initialPhase = 0.09,
        )
    private val secondary =
        WavetableOscillator(
            frequencyHz = profile.primaryFrequencyHz * profile.secondaryFrequencyRatio.toDouble(),
            sampleRate = sampleRate,
            table = table,
            initialPhase = 0.43,
        )
    private val highPass = BiquadFilter.highPass(sampleRate, 80.0, 0.707)
    private val lowPass =
        BiquadFilter.lowPass(
            sampleRate,
            3_800.0 + 6_000.0 * profile.harmonicRichness,
            0.707,
        )
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
            var mixed = primary.nextSample(pitch)
            if (profile.dualTone) {
                mixed = mixed * 0.72 + secondary.nextSample(pitch) * 0.42
            }
            val drive = 1.2 + profile.saturation * 5.2
            val saturated = softSaturate(mixed * expression.driveScale, drive)
            val filtered = lowPass.process(highPass.process(saturated))
            buffer[index] =
                (filtered * OUTPUT_GAIN)
                    .toFloat()
                    .coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
        }
    }

    override fun reset() {
        primary.reset()
        secondary.reset()
        highPass.reset()
        lowPass.reset()
        expression.reset()
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val OUTPUT_GAIN = 0.82
        const val MAX_OUTPUT = 0.95f
    }
}
