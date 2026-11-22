package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.AsymmetricSaturator
import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.HarmonicWavetable
import com.industrial.horn.audio.dsp.WavetableOscillator

/**
 * City e-bike electronic horn (01 §2.6), rendered as interleaved stereo with a centred,
 * channel-identical direct sound (01 §2.9: EBIKE direct sound is same-phase 50/50; stereo
 * decorrelation comes from the scene engine).
 *
 * Two closely spaced 2.7 kHz pulse-wave emitters create the characteristic metallic chirp, a low
 * 685 Hz body adds weight. The pulse wavetable is band-limited at construction
 * (`N_max = floor(0.45*fs/(2785*1.08)) = 7`, C2 ruling: the old 8th harmonic used to alias at
 * +8 % bend). The 4-7 kHz glitch band is suppressed by the doubled 4.2 kHz low-pass (24 dB/oct)
 * plus a -6 dB 5.5 kHz notch; the 1 ms DC switch click rides through a 2 kHz high-pass.
 * The 4/6 ms envelope stays in the voice profile (C5 ruling: "extreme snap" comes from the
 * exciter transients, not a shortened master envelope).
 */
class EBikeHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : NoteAwareSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val pulseTable =
        HarmonicWavetable(bandLimited(PULSE_HARMONICS, PULSE_F0_MAX, sampleRate))
    private val bodyTable = HarmonicWavetable(bandLimited(BODY_HARMONICS, BODY_F0, sampleRate))
    private val highA = WavetableOscillator(2_740.0, sampleRate, pulseTable, 0.07)
    private val highB = WavetableOscillator(2_785.0, sampleRate, pulseTable, 0.53)
    private val body = WavetableOscillator(BODY_F0, sampleRate, bodyTable, 0.21)

    private val saturator = AsymmetricSaturator()
    private val highPass = BiquadFilter.highPass(sampleRate, 500.0, 0.707)
    private val lowPassA = BiquadFilter.lowPass(sampleRate, GLITCH_LOW_PASS_HZ, 0.707)
    private val lowPassB = BiquadFilter.lowPass(sampleRate, GLITCH_LOW_PASS_HZ, 0.707)
    private val glitchNotch = BiquadFilter.peak(sampleRate, 5_500.0, 3.0, -6.0)
    private val dcHighPass = BiquadFilter.highPass(sampleRate, 2_000.0, 0.707)
    private val expression = ExpressionModulator(sampleRate)

    private var dcPulseRemaining = 0

    override fun setExpression(expression: Float) {
        this.expression.setTarget(expression)
    }

    override fun setNoteActive(active: Boolean) {
        if (active) dcPulseRemaining = DC_CLICK_SAMPLES
    }

    override fun render(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        require(frameCount in 0..buffer.size / 2) { "frameCount outside buffer" }

        for (index in 0 until frameCount) {
            expression.advance()
            val pitch = expression.pitchScale
            val mixed =
                highA.nextSample(pitch) * HIGH_A_GAIN +
                    highB.nextSample(pitch) * HIGH_B_GAIN +
                    body.nextSample(pitch) * BODY_GAIN

            val dc =
                if (dcPulseRemaining > 0) {
                    dcPulseRemaining--
                    DC_CLICK_AMPLITUDE
                } else {
                    0.0
                }
            val click = dcHighPass.process(dc)

            val saturated = saturator.shape(mixed + click, DRIVE * expression.driveScale, ASYMMETRY)
            val filtered =
                glitchNotch.process(
                    lowPassB.process(lowPassA.process(highPass.process(saturated))),
                )
            val sample = (filtered * OUTPUT_GAIN).toFloat().coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
            buffer[2 * index] = sample
            buffer[2 * index + 1] = sample
        }
    }

    override fun reset() {
        highA.reset()
        highB.reset()
        body.reset()
        highPass.reset()
        lowPassA.reset()
        lowPassB.reset()
        glitchNotch.reset()
        dcHighPass.reset()
        expression.reset()
        dcPulseRemaining = DC_CLICK_SAMPLES
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val PULSE_F0_MAX = 2_785.0
        const val BODY_F0 = 685.0

        // The 4-7 kHz glitch band: canonical 4.2 kHz low-pass (24 dB/oct), C7 ruling.
        const val GLITCH_LOW_PASS_HZ = 4_200.0

        const val HIGH_A_GAIN = 0.50
        const val HIGH_B_GAIN = 0.32
        const val BODY_GAIN = 0.13
        const val DRIVE = 1.85
        const val ASYMMETRY = 0.08
        const val OUTPUT_GAIN = 0.95
        const val MAX_OUTPUT = 0.95f

        const val DC_CLICK_AMPLITUDE = 0.03
        const val DC_CLICK_SAMPLES = 48 // 1 ms unipolar switch click

        // Rich, slightly asymmetric pulse spectrum; HarmonicWavetable normalises its peak.
        val PULSE_HARMONICS = doubleArrayOf(1.0, 0.42, 0.46, 0.22, 0.19, 0.10, 0.08, 0.05)
        val BODY_HARMONICS = doubleArrayOf(1.0, 0.28, 0.12)

        /** Dynamic band limit (C2): harmonics above N_max never enter the table. */
        fun bandLimited(
            gains: DoubleArray,
            maxFundamentalHz: Double,
            sampleRate: Int,
        ): DoubleArray {
            val limit = DspConstants.harmonicLimit(sampleRate, maxFundamentalHz)
            return if (gains.size <= limit) gains else gains.copyOf(limit)
        }
    }
}
