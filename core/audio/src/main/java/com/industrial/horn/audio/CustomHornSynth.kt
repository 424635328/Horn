package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.AmplitudeDelaySteepener
import com.industrial.horn.audio.dsp.AsymmetricSaturator
import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.HarmonicWavetable
import com.industrial.horn.audio.dsp.NoiseSource
import com.industrial.horn.audio.dsp.WavetableOscillator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pro Sound Lab voice (01 §2.8), rendered as interleaved stereo. The seven user sliders map onto
 * the unified physical model:
 *
 *  - `primaryFrequencyHz` -> body fundamental and horn cutoff `fc = 0.55*f0` clamped to
 *    [80, 1500] Hz (throat reactance high-pass);
 *  - `secondaryFrequencyRatio` -> second body `f0' = ratio*f0`;
 *  - `dualTone` -> two cross-panned horn bodies or one centred body;
 *  - `harmonicRichness` -> harmonic decay exponent 1.45 -> 1.15, brassiness cap and the
 *    mouth radiation high-pass 120 -> 80 Hz;
 *  - `attackMillis` -> relay-impact amplitude `clamp(0.05*(20/attack), 0, 0.05)`;
 *  - `releaseMillis` -> diaphragm damping `zeta = 0.01 + 0.04*(release/250)` and ring time
 *    `tau_off = release/2`;
 *  - `saturation` -> steepening `k = 1.2 + 5.2*sat`, `beta` and `alpha` of the two brassiness
 *    schemes on top of the expression-linked bases.
 *
 * Harmonic tables are band-limited at construction (`N_max = floor(0.45*fs/(f0'*1.08))`, C2
 * ruling: 12 harmonics at 3 kHz used to alias at 36 kHz). The profile is sanitized once on
 * construction; changing a slider rebuilds the voice at a silent boundary (unchanged contract).
 */
class CustomHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    rawProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
) : NoteAwareSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val profile = rawProfile.sanitized()
    private val secondaryFrequency =
        (profile.primaryFrequencyHz * profile.secondaryFrequencyRatio).toDouble()
    private val harmonics =
        bandLimited(
            harmonicWeights(profile.harmonicRichness),
            maxOf(profile.primaryFrequencyHz.toDouble(), secondaryFrequency),
            sampleRate,
        )
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
            frequencyHz = secondaryFrequency,
            sampleRate = sampleRate,
            table = table,
            initialPhase = 0.43,
        )

    // Horn body: throat reactance (fc = 0.55*f0) plus the mouth radiation high-pass (120->80 Hz).
    private val throatCoefficient =
        onePoleHighPassCoefficient(
            (0.55 * profile.primaryFrequencyHz).coerceIn(80.0, 1_500.0),
            sampleRate,
        )
    private val radiationCoefficient =
        onePoleHighPassCoefficient(120.0 - 40.0 * profile.harmonicRichness, sampleRate)

    private val steepenerL = AmplitudeDelaySteepener(sampleRate, STEEPENER_DELAY_MS)
    private val steepenerR = AmplitudeDelaySteepener(sampleRate, STEEPENER_DELAY_MS)
    private val saturator = AsymmetricSaturator()
    private val lowPassL =
        BiquadFilter.lowPass(sampleRate, 3_800.0 + 6_000.0 * profile.harmonicRichness, 0.707)
    private val lowPassR =
        BiquadFilter.lowPass(sampleRate, 3_800.0 + 6_000.0 * profile.harmonicRichness, 0.707)
    private val relayBand = BiquadFilter.bandPass(sampleRate, 1_500.0, 1.2)
    private val noise = NoiseSource(0x1CEDC0DE)
    private val expression = ExpressionModulator(sampleRate)

    private val driveBase = 1.2 + profile.saturation * 5.2
    private val betaBase = profile.saturation * 0.30
    private val alphaBase = profile.saturation * 0.15
    private val relayAmplitude =
        (0.05 * (20.0 / profile.attackMillis)).coerceIn(0.0, 0.05)
    private val relayDecay = exp(-1.0 / (sampleRate * 0.003))
    private val ringZeta = 0.01 + 0.04 * (profile.releaseMillis / 250f)
    private val ringTauOff = profile.releaseMillis / 2_000f
    private val ringPole = exp(-ringZeta * TWO_PI * RING_F0 / sampleRate)
    private val ringTheta = TWO_PI * RING_F0 * sqrt(1.0 - ringZeta * ringZeta) / sampleRate
    private val ringA1 = -2.0 * ringPole * cos(ringTheta)
    private val ringA2 = ringPole * ringPole
    private val ringDecay = exp(-1.0 / (sampleRate * ringTauOff))

    private var throatStateL = 0.0
    private var throatStateR = 0.0
    private var throatX1L = 0.0
    private var throatX1R = 0.0
    private var radiationStateL = 0.0
    private var radiationStateR = 0.0
    private var radiationX1L = 0.0
    private var radiationX1R = 0.0
    private var clickEnvelope = relayAmplitude
    private var ringY1 = 0.0
    private var ringY2 = 0.0
    private var ringEnvelope = 0.0

    override fun setExpression(expression: Float) {
        this.expression.setTarget(expression)
    }

    override fun setNoteActive(active: Boolean) {
        if (active) {
            clickEnvelope = relayAmplitude
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
            val primarySample = primary.nextSample(pitch)

            var mixedL: Double
            var mixedR: Double
            if (profile.dualTone) {
                val secondarySample = secondary.nextSample(pitch)
                val lowBody = primarySample * PRIMARY_WEIGHT
                val highBody = secondarySample * SECONDARY_WEIGHT
                mixedL = lowBody * BODY_GAIN_L + highBody * BODY_GAIN_R
                mixedR = lowBody * BODY_GAIN_R + highBody * BODY_GAIN_L
            } else {
                mixedL = primarySample
                mixedR = primarySample
            }

            val relay = relayBand.process(noise.nextWhite()) * clickEnvelope
            clickEnvelope *= relayDecay
            val ring = (-ringA1 * ringY1 - ringA2 * ringY2) * ringEnvelope
            ringY2 = ringY1
            ringY1 = ring
            ringEnvelope *= ringDecay
            mixedL += relay + ring
            mixedR += relay + ring

            // Horn body: throat reactance then mouth radiation (one-pole high-passes).
            val throatL = throatCoefficient * (throatStateL + mixedL - throatX1L)
            val throatR = throatCoefficient * (throatStateR + mixedR - throatX1R)
            throatX1L = mixedL
            throatX1R = mixedR
            throatStateL = throatL
            throatStateR = throatR
            val radiationL = radiationCoefficient * (radiationStateL + throatL - radiationX1L)
            val radiationR = radiationCoefficient * (radiationStateR + throatR - radiationX1R)
            radiationX1L = throatL
            radiationX1R = throatR
            radiationStateL = radiationL
            radiationStateR = radiationR

            val steepenedL = steepenerL.process(radiationL, expression.brassBeta + betaBase)
            val steepenedR = steepenerR.process(radiationR, expression.brassBeta + betaBase)

            val k = (driveBase * expression.driveScale).coerceIn(0.25, 6.0)
            val alpha = (expression.brassAsym + alphaBase).coerceAtMost(0.25)
            buffer[2 * index] =
                finish(lowPassL, saturator.shape(steepenedL, k, alpha))
            buffer[2 * index + 1] =
                finish(lowPassR, saturator.shape(steepenedR, k, alpha))
        }
    }

    private fun finish(
        lowPass: BiquadFilter,
        shaped: Double,
    ): Float {
        val filtered = lowPass.process(shaped)
        return (filtered * OUTPUT_GAIN).toFloat().coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
    }

    override fun reset() {
        primary.reset()
        secondary.reset()
        steepenerL.reset()
        steepenerR.reset()
        lowPassL.reset()
        lowPassR.reset()
        relayBand.reset()
        noise.reset()
        expression.reset()
        throatStateL = 0.0
        throatStateR = 0.0
        throatX1L = 0.0
        throatX1R = 0.0
        radiationStateL = 0.0
        radiationStateR = 0.0
        radiationX1L = 0.0
        radiationX1R = 0.0
        clickEnvelope = relayAmplitude
        ringY1 = 0.0
        ringY2 = 0.0
        ringEnvelope = 0.0
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val OUTPUT_GAIN = 1.0
        const val MAX_OUTPUT = 0.95f
        const val STEEPENER_DELAY_MS = 0.8
        const val PRIMARY_WEIGHT = 0.72
        const val SECONDARY_WEIGHT = 0.42
        const val RING_F0 = 440.0
        const val RING_EXCITATION = 0.2
        const val TWO_PI = 2.0 * PI

        // Cross-pan of the two horn bodies, normalised to (g_L+g_R)/2 = 1 (mono-fold safe).
        const val BODY_GAIN_L = 1.16
        const val BODY_GAIN_R = 0.84

        /** 01 §2.8 harmonic decay: exponent 1.45 -> 1.15 with the v3 richness amplitude tilt. */
        fun harmonicWeights(richness: Float): DoubleArray {
            val exponent = 1.45 - 0.30 * richness
            val tilt = 0.22 + 0.78 * richness
            return DoubleArray(12) { index ->
                if (index == 0) 1.0 else (index + 1).toDouble().pow(-exponent) * tilt
            }
        }

        /** Dynamic band limit (C2): harmonics above N_max never enter the table. */
        fun bandLimited(
            gains: DoubleArray,
            maxFundamentalHz: Double,
            sampleRate: Int,
        ): DoubleArray {
            val limit = DspConstants.harmonicLimit(sampleRate, maxFundamentalHz)
            return if (gains.size <= limit) gains else gains.copyOf(limit)
        }

        private fun onePoleHighPassCoefficient(
            cutoffHz: Double,
            sampleRate: Int,
        ): Double = exp(-TWO_PI * cutoffHz / sampleRate)
    }
}
