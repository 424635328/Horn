package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.HarmonicWavetable
import com.industrial.horn.audio.dsp.NoiseSource
import com.industrial.horn.audio.dsp.WavetableOscillator
import com.industrial.horn.audio.dsp.smoothStep
import com.industrial.horn.audio.dsp.softSaturate
import kotlin.math.min

/**
 * Three-chime heavy pneumatic locomotive horn.
 *
 * The chord is the classic D#4/F#4/B4 Leslie/Nathan stack (311/370/494 Hz), doubled with a
 * small detune per note, reinforced by D#3 and B2 sub-octaves. Compressed air is represented by
 * band-passed noise and a short downward pitch bend at note-on; the slow envelope (65 ms attack,
 * 185 ms release) is supplied by the voice profile.
 */
class TrainHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : HornSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val chordTable = HarmonicWavetable(CHORD_HARMONICS)
    private val subTable = HarmonicWavetable(SUB_HARMONICS)
    private val tones =
        arrayOf(
            Tone(311.13 * 0.996, 0.18, chordTable, 0.03, sampleRate),
            Tone(311.13 * 1.004, 0.14, chordTable, 0.41, sampleRate),
            Tone(369.99 * 0.996, 0.16, chordTable, 0.17, sampleRate),
            Tone(369.99 * 1.004, 0.13, chordTable, 0.63, sampleRate),
            Tone(493.88 * 0.996, 0.14, chordTable, 0.29, sampleRate),
            Tone(493.88 * 1.004, 0.11, chordTable, 0.77, sampleRate),
            Tone(155.56, 0.10, subTable, 0.11, sampleRate),
            Tone(123.47, 0.06, subTable, 0.53, sampleRate),
        )

    private val airNoise = NoiseSource(0x51A1C0DE)
    private val airBand = BiquadFilter.bandPass(sampleRate, 1_800.0, 0.7)
    private val highPass = BiquadFilter.highPass(sampleRate, 80.0, 0.707)
    private val lowPass = BiquadFilter.lowPass(sampleRate, 5_600.0, 0.707)
    private val bendSamples = (sampleRate * PITCH_BEND_SECONDS).toInt()
    private val expression = ExpressionModulator()

    private var ageSamples = 0

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
            val bendProgress =
                if (bendSamples > 0) {
                    ageSamples.toDouble() / bendSamples
                } else {
                    1.0
                }
            val bend = BEND_START + (1.0 - BEND_START) * smoothStep(min(1.0, bendProgress))

            var chord = 0.0
            for (tone in tones) {
                chord += tone.oscillator.nextSample(bend * expression.pitchScale) * tone.gain
            }

            val air = airBand.process(airNoise.nextWhite()) * AIR_LEVEL * expression.noiseScale
            val mixed = chord + air
            val saturated = softSaturate(mixed, DRIVE * expression.driveScale)
            val focused = lowPass.process(highPass.process(saturated))

            buffer[index] =
                (focused * OUTPUT_GAIN)
                    .toFloat()
                    .coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
            ageSamples++
        }
    }

    override fun reset() {
        for (tone in tones) tone.oscillator.reset()
        airNoise.reset()
        airBand.reset()
        highPass.reset()
        lowPass.reset()
        ageSamples = 0
        expression.reset()
    }

    private class Tone(
        frequencyHz: Double,
        val gain: Double,
        table: HarmonicWavetable,
        initialPhase: Double,
        sampleRate: Int,
    ) {
        val oscillator =
            WavetableOscillator(
                frequencyHz = frequencyHz,
                sampleRate = sampleRate,
                table = table,
                initialPhase = initialPhase,
            )
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val PITCH_BEND_SECONDS = 0.18
        const val BEND_START = 0.982
        const val AIR_LEVEL = 0.055
        const val DRIVE = 1.6
        const val OUTPUT_GAIN = 0.88
        const val MAX_OUTPUT = 0.95f

        val CHORD_HARMONICS = doubleArrayOf(1.0, 0.58, 0.37, 0.24, 0.15, 0.10, 0.065, 0.04)
        val SUB_HARMONICS = doubleArrayOf(1.0, 0.32, 0.16, 0.08)
    }
}
