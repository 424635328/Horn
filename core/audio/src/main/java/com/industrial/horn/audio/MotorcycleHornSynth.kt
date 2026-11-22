package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.AsymmetricSaturator
import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.HarmonicWavetable
import com.industrial.horn.audio.dsp.WavetableOscillator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Compact single-tone motorcycle disc horn (01 §2.7), rendered as interleaved stereo with a
 * centred, channel-identical direct sound (01 §2.9: MOTORCYCLE direct sound is same-phase 50/50;
 * stereo decorrelation comes from the scene engine).
 *
 * A 444 Hz metal diaphragm with strong odd/even harmonics drives the nose formant group
 * (680 Hz +4 dB, 1050 Hz +3 dB) and is modulated by the electromagnetic contact flutter:
 * AM at 29 Hz with depth 0.18 plus a 0.3 % FM micro-wobble (both inside the frozen 25-38 Hz /
 * 12-18 % / <= 0.5 % bounds). The 190 Hz high-pass removes sub-bass and the 4.8 kHz low-pass keeps
 * the sound focused; scheme-2 asymmetric saturation (`k = 2.0*driveScale`, `alpha = 0.12`)
 * replaces the old tanh saturator. The 8/10 ms tap envelope stays in the voice profile (C5).
 */
class MotorcycleHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : NoteAwareSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val table = HarmonicWavetable(bandLimited(DIAPHRAGM_HARMONICS, FUNDAMENTAL_HZ, sampleRate))
    private val oscillator = WavetableOscillator(FUNDAMENTAL_HZ, sampleRate, table, 0.13)
    private val formantLow = BiquadFilter.peak(sampleRate, 680.0, 1.5, 4.0)
    private val formantHigh = BiquadFilter.peak(sampleRate, 1_050.0, 2.0, 3.0)
    private val saturator = AsymmetricSaturator()
    private val highPass = BiquadFilter.highPass(sampleRate, 190.0, 0.707)
    private val lowPass = BiquadFilter.lowPass(sampleRate, 4_800.0, 0.707)
    private val modulationIncrement = 2.0 * PI * FLUTTER_HZ / sampleRate
    private val fmIncrement = 2.0 * PI * FLUTTER_HZ / sampleRate
    private val expression = ExpressionModulator(sampleRate)

    private var modulationPhase = 0.0
    private var fmPhase = 0.0

    override fun setExpression(expression: Float) {
        this.expression.setTarget(expression)
    }

    override fun setNoteActive(active: Boolean) = Unit

    override fun render(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        require(frameCount in 0..buffer.size / 2) { "frameCount outside buffer" }

        for (index in 0 until frameCount) {
            expression.advance()
            modulationPhase += modulationIncrement
            if (modulationPhase >= 2.0 * PI) modulationPhase -= 2.0 * PI
            fmPhase += fmIncrement
            if (fmPhase >= 2.0 * PI) fmPhase -= 2.0 * PI

            val flutter = 1.0 + FLUTTER_DEPTH * sin(modulationPhase)
            val fmScale = 1.0 + FLUTTER_FM * sin(fmPhase)

            // The contact flutter modulates the radiated disc motion, i.e. after the diaphragm
            // nonlinearity and nose formants, so its AM depth survives saturation.
            val mixed = oscillator.nextSample(expression.pitchScale * fmScale)
            val saturated = saturator.shape(mixed, DRIVE * expression.driveScale, ASYMMETRY)
            val formed = formantHigh.process(formantLow.process(saturated))
            val focused = lowPass.process(highPass.process(formed * flutter))
            val sample = (focused * OUTPUT_GAIN).toFloat().coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
            buffer[2 * index] = sample
            buffer[2 * index + 1] = sample
        }
    }

    override fun reset() {
        oscillator.reset()
        formantLow.reset()
        formantHigh.reset()
        highPass.reset()
        lowPass.reset()
        modulationPhase = 0.0
        fmPhase = 0.0
        expression.reset()
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val FUNDAMENTAL_HZ = 444.0
        const val FLUTTER_HZ = 29.0
        const val FLUTTER_DEPTH = 0.18
        const val FLUTTER_FM = 0.003
        const val DRIVE = 2.0
        const val ASYMMETRY = 0.12
        const val OUTPUT_GAIN = 0.82
        const val MAX_OUTPUT = 0.95f

        val DIAPHRAGM_HARMONICS =
            doubleArrayOf(1.0, 0.50, 0.36, 0.25, 0.17, 0.11, 0.07, 0.045)

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
