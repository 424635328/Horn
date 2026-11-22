package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.AmplitudeDelaySteepener
import com.industrial.horn.audio.dsp.AsymmetricSaturator
import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.NoiseSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Real-time model of a pair of automotive snail horns (01 §2.4), rendered as interleaved stereo.
 *
 * Physical layers, bottom-up:
 *  - two exponential horn bodies at 400 Hz / 500 Hz with the frozen harmonic stacks (band-limited
 *    at construction by `N_max = floor(0.45*fs/(f0*1.08))`, C2 ruling);
 *  - per-body brassiness: amplitude-dependent fractional delay (01 §2.3 scheme 1, d0 = 0.8 ms)
 *    driven by the expression-linked beta;
 *  - throat formant group (F1 700 Hz, F2 1.4 kHz +2 dB, F3 2.8 kHz +4 dB, 01 §2.2.3);
 *  - electromagnetic-diaphragm asymmetry via scheme 2 (`k = 1.55*driveScale`, `alpha` from
 *    expression, replacing the old tanh saturator);
 *  - deterministic air-turbulence noise bed with the 5.5 Hz sway plus a 0.4 Hz slow body beat
 *    (total sway <= 0.03, 01 §2.4.2);
 *  - 12 V relay mechanical click at note-on (1 ms pre-impact + synchronous burst, 01 §2.4.1) and
 *    the damped free ring after power-off (zeta = 0.02, f0 = 440 Hz, tau_off = 45 ms).
 *
 * Stereo placement (01 §2.4.2): the low body leans left and the high body right with
 * amplitude-dominant gains normalised so `(g_L+g_R)/2 = 1` (mono-fold preserving, 02 §5 C1);
 * noise and mechanical transients stay centred and channel-cosigned.
 */
class DualToneHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : NoteAwareSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val lowTone =
        ToneGenerator(
            frequency = LOW_F0,
            sampleRate = sampleRate,
            harmonicGains = bandLimited(LOW_HARMONICS, LOW_F0, sampleRate),
        )
    private val highTone =
        ToneGenerator(
            frequency = HIGH_F0,
            sampleRate = sampleRate,
            harmonicGains = bandLimited(HIGH_HARMONICS, HIGH_F0, sampleRate),
        )
    private val lowSteepener = AmplitudeDelaySteepener(sampleRate, STEEPENER_DELAY_MS)
    private val highSteepener = AmplitudeDelaySteepener(sampleRate, STEEPENER_DELAY_MS)
    private val saturator = AsymmetricSaturator()

    // Throat reactance of each exponential horn body (01 §2.2.1/§2.4.1, one-pole 260/330 Hz).
    private val throatLowCoefficient = onePoleHighPassCoefficient(260.0, sampleRate)
    private val throatHighCoefficient = onePoleHighPassCoefficient(330.0, sampleRate)
    private var throatLowX1 = 0.0
    private var throatLowY1 = 0.0
    private var throatHighX1 = 0.0
    private var throatHighY1 = 0.0

    private val formantL =
        arrayOf(
            BiquadFilter.peak(sampleRate, 700.0, 2.0, 0.0),
            BiquadFilter.peak(sampleRate, 1_400.0, 2.5, 2.0),
            BiquadFilter.peak(sampleRate, 2_800.0, 3.0, 4.0),
        )
    private val formantR =
        arrayOf(
            BiquadFilter.peak(sampleRate, 700.0, 2.0, 0.0),
            BiquadFilter.peak(sampleRate, 1_400.0, 2.5, 2.0),
            BiquadFilter.peak(sampleRate, 2_800.0, 3.0, 4.0),
        )
    private val lowPassL = BiquadFilter.lowPass(sampleRate, LOW_PASS_HZ, 0.707)
    private val lowPassR = BiquadFilter.lowPass(sampleRate, LOW_PASS_HZ, 0.707)

    private val noise = NoiseSource(0x4D595DF4)
    private val relayBand = BiquadFilter.bandPass(sampleRate, 1_500.0, 1.2)

    private val expression = ExpressionModulator(sampleRate)
    private val swayIncrement = TWO_PI * SWAY_HZ / sampleRate
    private val beatIncrement = TWO_PI * BEAT_HZ / sampleRate
    private val clickDecay = exp(-1.0 / (sampleRate * RELAY_TAU_SECONDS))
    private val ringDecay = exp(-1.0 / (sampleRate * RING_TAU_OFF_SECONDS))
    private val ringPole = exp(-RING_ZETA * TWO_PI * RING_F0 / sampleRate)
    private val ringTheta = TWO_PI * RING_F0 * kotlin.math.sqrt(1.0 - RING_ZETA * RING_ZETA) / sampleRate
    private val ringA1 = -2.0 * ringPole * cos(ringTheta)
    private val ringA2 = ringPole * ringPole

    private var noiseLowPass = 0.0
    private var swayPhase = 0.0
    private var beatPhase = 0.0
    private var clickEnvelope = RELAY_AMPLITUDE
    private var clickDelay = RELAY_SECOND_BURST_SAMPLES
    private var ringY1 = 0.0
    private var ringY2 = 0.0
    private var ringEnvelope = 0.0

    override fun setExpression(expression: Float) {
        this.expression.setTarget(expression)
    }

    override fun setNoteActive(active: Boolean) {
        if (active) {
            clickEnvelope = RELAY_AMPLITUDE
            clickDelay = RELAY_SECOND_BURST_SAMPLES
        } else {
            ringY1 += RING_EXCITATION
            ringEnvelope = 1.0
        }
    }

    override fun render(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        require(frameCount in 0..buffer.size / 2) { "frameCount outside buffer" }

        for (index in 0 until frameCount) {
            expression.advance()
            val pitch = expression.pitchScale
            val rawLow = lowTone.nextSample(pitch)
            val throatLow = throatLowCoefficient * (throatLowY1 + rawLow - throatLowX1)
            throatLowX1 = rawLow
            throatLowY1 = throatLow
            val rawHigh = highTone.nextSample(pitch)
            val throatHigh = throatHighCoefficient * (throatHighY1 + rawHigh - throatHighX1)
            throatHighX1 = rawHigh
            throatHighY1 = throatHigh
            val low = lowSteepener.process(throatLow, expression.brassBeta)
            val high = highSteepener.process(throatHigh, expression.brassBeta)

            val white = noise.nextWhite()
            noiseLowPass += NOISE_SMOOTHING * (white - noiseLowPass)

            swayPhase += swayIncrement
            if (swayPhase >= TWO_PI) swayPhase -= TWO_PI
            beatPhase += beatIncrement
            if (beatPhase >= TWO_PI) beatPhase -= TWO_PI
            val sway =
                (1.0 + SWAY_DEPTH * sin(swayPhase)) * (1.0 + BEAT_DEPTH * sin(beatPhase))

            if (clickDelay > 0) {
                clickDelay--
                if (clickDelay == 0) clickEnvelope = RELAY_AMPLITUDE
            }
            val relay = relayBand.process(noise.nextWhite()) * clickEnvelope
            clickEnvelope *= clickDecay

            val ring = (-ringA1 * ringY1 - ringA2 * ringY2) * ringEnvelope
            ringY2 = ringY1
            ringY1 = ring
            ringEnvelope *= ringDecay

            val centre =
                (noiseLowPass * NOISE_LEVEL * expression.noiseScale + relay + ring) * sway
            val lowPart = low * LOW_WEIGHT
            val highPart = high * HIGH_WEIGHT

            val mixedL = (lowPart * LOW_GAIN_L + highPart * HIGH_GAIN_L) * sway + centre
            val mixedR = (lowPart * LOW_GAIN_R + highPart * HIGH_GAIN_R) * sway + centre

            val k = DRIVE * expression.driveScale
            val alpha = expression.brassAsym
            buffer[2 * index] = finish(formantL, lowPassL, saturator.shape(mixedL, k, alpha))
            buffer[2 * index + 1] = finish(formantR, lowPassR, saturator.shape(mixedR, k, alpha))
        }
    }

    private fun finish(
        formants: Array<BiquadFilter>,
        lowPass: BiquadFilter,
        shaped: Double,
    ): Float {
        var filtered = shaped
        for (formant in formants) filtered = formant.process(filtered)
        val focused = lowPass.process(filtered)
        return (focused * OUTPUT_GAIN).toFloat().coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
    }

    override fun reset() {
        lowTone.reset()
        highTone.reset()
        lowSteepener.reset()
        highSteepener.reset()
        throatLowX1 = 0.0
        throatLowY1 = 0.0
        throatHighX1 = 0.0
        throatHighY1 = 0.0
        for (formant in formantL) formant.reset()
        for (formant in formantR) formant.reset()
        lowPassL.reset()
        lowPassR.reset()
        noise.reset()
        relayBand.reset()
        expression.reset()
        noiseLowPass = 0.0
        swayPhase = 0.0
        beatPhase = 0.0
        clickEnvelope = RELAY_AMPLITUDE
        clickDelay = RELAY_SECOND_BURST_SAMPLES
        ringY1 = 0.0
        ringY2 = 0.0
        ringEnvelope = 0.0
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
        const val LOW_F0 = 400.0
        const val HIGH_F0 = 500.0
        const val LOW_PASS_HZ = 6_000.0
        const val SWAY_HZ = 5.5
        const val SWAY_DEPTH = 0.018
        const val BEAT_HZ = 0.4
        const val BEAT_DEPTH = 0.007
        const val NOISE_LEVEL = 0.0055
        const val NOISE_SMOOTHING = 0.35
        const val DRIVE = 1.55
        const val OUTPUT_GAIN = 0.82
        const val MAX_OUTPUT = 0.95f
        const val LOW_WEIGHT = 0.52
        const val HIGH_WEIGHT = 0.48
        const val HARMONIC_NORMALIZATION = 0.43
        const val STEEPENER_DELAY_MS = 0.8

        // Amplitude-dominant placement with mono-fold preserving normalisation (02 §5 C1).
        const val LOW_GAIN_L = 1.16
        const val LOW_GAIN_R = 0.84
        const val HIGH_GAIN_L = 0.84
        const val HIGH_GAIN_R = 1.16

        const val RELAY_AMPLITUDE = 0.05
        const val RELAY_TAU_SECONDS = 0.003
        const val RELAY_SECOND_BURST_SAMPLES = 48 // 1 ms after note-on

        const val RING_ZETA = 0.02
        const val RING_F0 = 440.0
        const val RING_TAU_OFF_SECONDS = 0.045
        const val RING_EXCITATION = 0.35

        val LOW_HARMONICS = doubleArrayOf(1.0, 0.62, 0.44, 0.30, 0.20, 0.14, 0.10, 0.07)
        val HIGH_HARMONICS = doubleArrayOf(1.0, 0.55, 0.42, 0.28, 0.18, 0.12, 0.08)

        /** Pole of the one-pole throat-reactance high-pass (01 §2.2.1). */
        fun onePoleHighPassCoefficient(
            cutoffHz: Double,
            sampleRate: Int,
        ): Double = exp(-TWO_PI * cutoffHz / sampleRate)

        /** Dynamic band limit (C2): harmonics above N_max never enter the table. */
        fun bandLimited(
            gains: DoubleArray,
            fundamentalHz: Double,
            sampleRate: Int,
        ): DoubleArray {
            val limit = DspConstants.harmonicLimit(sampleRate, fundamentalHz)
            return if (gains.size <= limit) gains else gains.copyOf(limit)
        }

        const val TWO_PI = 2.0 * PI
    }
}
