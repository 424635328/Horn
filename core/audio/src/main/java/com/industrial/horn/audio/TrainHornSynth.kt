package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.AmplitudeDelaySteepener
import com.industrial.horn.audio.dsp.AsymmetricSaturator
import com.industrial.horn.audio.dsp.BiquadFilter
import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.ExpressionModulator
import com.industrial.horn.audio.dsp.HarmonicWavetable
import com.industrial.horn.audio.dsp.NoiseSource
import com.industrial.horn.audio.dsp.WavetableOscillator
import kotlin.math.exp

/**
 * Heavy pneumatic locomotive horn, 3-5 chimes (01 §2.5), rendered as interleaved stereo.
 *
 * The chord is the Leslie/Nathan stack E♭4/F♯4/B4 reinforced by the E♭3 and B2 sub-octaves; each
 * chime is a ±0.4 % micro-detune pair and each voice owns an independent air chamber
 * (`dp/dt = (p0-p)/tau_v`, exact discrete step) with the frozen valve stagger table (opens spread
 * over 0-45 ms, closes over 0-45 ms).
 *
 * Pitch is coupled to the chamber pressure (C4 ruling): at valve open the onset sag is -1.8 % and
 * decays back to zero with a 45 ms one-pole, while `f = f0*(1+kappa*(p/p0-1))` produces the
 * pressure micro-slide; the total offset is clamped to [-1.9 %, +1.5 %] and the old independent
 * `BEND_START` mechanism is gone. Drive, brassiness and air noise all follow the same pressure
 * (`drive*(0.6+0.4p)`, `beta*p`, `L_air = 0.055*(0.5+0.5p)`).
 *
 * Stereo: per-note amplitude pans from 01 §2.5.1 (B2 centre, E♭3 L, F♯4 R, E♭4 L, B4 R) with
 * mono-fold preserving gains; detune partners share one pan so beat depth is channel-cosigned
 * (02 §5). The air-noise band (1.2 kHz + 2.4 kHz RBJ band-passes) stays centred.
 */
class TrainHornSynth(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : NoteAwareSynthesizer {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
    }

    private val chordTable =
        HarmonicWavetable(bandLimited(CHORD_HARMONICS, HIGHEST_CHIME_HZ, sampleRate))
    private val subTable = HarmonicWavetable(bandLimited(SUB_HARMONICS, HIGHEST_CHIME_HZ, sampleRate))

    private val voices =
        arrayOf(
            // t_on/t_off and tau_open/tau_close are the frozen 01 §2.5.2 stagger table.
            ValveVoice(
                frequencyHz = 123.47,
                openDelaySeconds = 0.000,
                closeDelaySeconds = 0.000,
                tauOpenSeconds = 0.015,
                tauCloseSeconds = 0.030,
                gainLeft = PAN_C_L,
                gainRight = PAN_C_R,
                table = subTable,
                oscGains = doubleArrayOf(GAIN_B2),
                sampleRate = sampleRate,
            ),
            ValveVoice(
                frequencyHz = 155.56,
                openDelaySeconds = 0.012,
                closeDelaySeconds = 0.011,
                tauOpenSeconds = 0.018,
                tauCloseSeconds = 0.032,
                gainLeft = PAN_L3_L,
                gainRight = PAN_L3_R,
                table = subTable,
                oscGains = doubleArrayOf(GAIN_EB3),
                sampleRate = sampleRate,
            ),
            ValveVoice(
                frequencyHz = 369.99,
                openDelaySeconds = 0.024,
                closeDelaySeconds = 0.022,
                tauOpenSeconds = 0.022,
                tauCloseSeconds = 0.035,
                gainLeft = PAN_R2_L,
                gainRight = PAN_R2_R,
                table = chordTable,
                oscGains = doubleArrayOf(GAIN_FS4_A, GAIN_FS4_B),
                detuneRatios = DETUNE_PAIR,
                sampleRate = sampleRate,
            ),
            ValveVoice(
                frequencyHz = 311.13,
                openDelaySeconds = 0.036,
                closeDelaySeconds = 0.033,
                tauOpenSeconds = 0.025,
                tauCloseSeconds = 0.038,
                gainLeft = PAN_L2_L,
                gainRight = PAN_L2_R,
                table = chordTable,
                oscGains = doubleArrayOf(GAIN_EB4_A, GAIN_EB4_B),
                detuneRatios = DETUNE_PAIR,
                sampleRate = sampleRate,
            ),
            ValveVoice(
                frequencyHz = 493.88,
                openDelaySeconds = 0.045,
                closeDelaySeconds = 0.045,
                tauOpenSeconds = 0.028,
                tauCloseSeconds = 0.040,
                gainLeft = PAN_R3_L,
                gainRight = PAN_R3_R,
                table = chordTable,
                oscGains = doubleArrayOf(GAIN_B4_A, GAIN_B4_B),
                detuneRatios = DETUNE_PAIR,
                sampleRate = sampleRate,
            ),
        )

    private val airNoise = NoiseSource(0x51A1C0DE)
    private val airBandLow = BiquadFilter.bandPass(sampleRate, 1_200.0, 0.8)
    private val airBandHigh = BiquadFilter.bandPass(sampleRate, 2_400.0, 1.0)
    private val steepenerL = AmplitudeDelaySteepener(sampleRate, STEEPENER_DELAY_MS)
    private val steepenerR = AmplitudeDelaySteepener(sampleRate, STEEPENER_DELAY_MS)
    private val saturator = AsymmetricSaturator()
    private val highPassL = BiquadFilter.highPass(sampleRate, 80.0, 0.707)
    private val highPassR = BiquadFilter.highPass(sampleRate, 80.0, 0.707)
    private val lowPassL = BiquadFilter.lowPass(sampleRate, 5_600.0, 0.707)
    private val lowPassR = BiquadFilter.lowPass(sampleRate, 5_600.0, 0.707)
    private val expression = ExpressionModulator(sampleRate)

    override fun setExpression(expression: Float) {
        this.expression.setTarget(expression)
    }

    override fun setNoteActive(active: Boolean) {
        for (voice in voices) voice.scheduleValve(active)
    }

    override fun render(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        require(frameCount in 0..buffer.size / 2) { "frameCount outside buffer" }

        for (index in 0 until frameCount) {
            expression.advance()

            var chordL = 0.0
            var chordR = 0.0
            var pressure = 0.0
            for (voice in voices) {
                voice.stepValves()
                pressure += voice.pressure
                val scale = voice.pitchScale(expression.pitchScale)
                val tone = voice.sample(scale)
                chordL += tone * voice.gainLeft
                chordR += tone * voice.gainRight
            }
            val pressureMean = pressure / voices.size

            val steepenedL = steepenerL.process(chordL, expression.brassBeta * pressureMean)
            val steepenedR = steepenerR.process(chordR, expression.brassBeta * pressureMean)

            val air =
                airBandHigh.process(airBandLow.process(airNoise.nextWhite())) *
                    AIR_LEVEL * (0.5 + 0.5 * pressureMean) * expression.noiseScale

            val drive = DRIVE * expression.driveScale * (0.6 + 0.4 * pressureMean)
            val alpha = expression.brassAsym
            buffer[2 * index] =
                finish(highPassL, lowPassL, saturator.shape(steepenedL + air, drive, alpha))
            buffer[2 * index + 1] =
                finish(highPassR, lowPassR, saturator.shape(steepenedR + air, drive, alpha))
        }
    }

    private fun finish(
        highPass: BiquadFilter,
        lowPass: BiquadFilter,
        shaped: Double,
    ): Float {
        val focused = lowPass.process(highPass.process(shaped))
        return (focused * OUTPUT_GAIN).toFloat().coerceIn(-MAX_OUTPUT, MAX_OUTPUT)
    }

    override fun reset() {
        for (voice in voices) voice.reset()
        airNoise.reset()
        airBandLow.reset()
        airBandHigh.reset()
        steepenerL.reset()
        steepenerR.reset()
        highPassL.reset()
        highPassR.reset()
        lowPassL.reset()
        lowPassR.reset()
        expression.reset()
        // reset() is the note-start boundary for envelope-less (direct) rendering.
        for (voice in voices) voice.scheduleValve(true)
    }

    /**
     * One chime voice: micro-detune oscillators plus its air chamber, valve timing, pressure
     * pitch coupling and onset sag (01 §2.5.2/§2.5.3, C4 ruling).
     */
    private class ValveVoice(
        frequencyHz: Double,
        private val openDelaySeconds: Double,
        private val closeDelaySeconds: Double,
        tauOpenSeconds: Double,
        tauCloseSeconds: Double,
        val gainLeft: Double,
        val gainRight: Double,
        table: HarmonicWavetable,
        oscGains: DoubleArray,
        detuneRatios: DoubleArray = doubleArrayOf(1.0),
        sampleRate: Int,
    ) {
        private val openCoeff = 1.0 - exp(-1.0 / (sampleRate * tauOpenSeconds))
        private val closeCoeff = 1.0 - exp(-1.0 / (sampleRate * tauCloseSeconds))
        private val sagCoeff =
            1.0 - exp(-1.0 / (sampleRate * DspConstants.TRAIN_PITCH_SAG_TAU_MILLIS / 1_000.0))
        private val delayScale = sampleRate.toDouble()

        private val oscillators =
            Array(detuneRatios.size) { pair ->
                OscillatorWithGain(
                    oscillator =
                        WavetableOscillator(
                            frequencyHz = frequencyHz * detuneRatios[pair],
                            sampleRate = sampleRate,
                            table = table,
                            initialPhase = PHASES[pair % PHASES.size],
                        ),
                    gain = oscGains[pair],
                )
            }

        var pressure = 0.0
            private set
        private var sag = 0.0
        private var target = 0.0
        private var delaySamples = 0

        fun scheduleValve(open: Boolean) {
            target = if (open) 1.0 else 0.0
            delaySamples = ((if (open) openDelaySeconds else closeDelaySeconds) * delayScale).toInt()
        }

        fun stepValves() {
            if (delaySamples > 0) {
                delaySamples--
                if (delaySamples == 0 && target > 0.5) sag = DspConstants.TRAIN_PITCH_SAG_ONSET
            } else {
                val coefficient = if (target > pressure) openCoeff else closeCoeff
                pressure += (target - pressure) * coefficient
            }
            sag += (0.0 - sag) * sagCoeff
        }

        fun pitchScale(expressionScale: Double): Double {
            val offset =
                (sag + DspConstants.TRAIN_PITCH_KAPPA * (pressure - 1.0))
                    .coerceIn(DspConstants.TRAIN_PITCH_SAG_MIN, DspConstants.TRAIN_PITCH_SAG_MAX)
            return (1.0 + offset) * expressionScale
        }

        /** Advances every oscillator of this voice exactly once and returns the summed tone. */
        fun sample(scale: Double): Double {
            var sum = 0.0
            for (entry in oscillators) {
                sum += entry.oscillator.nextSample(scale) * entry.gain
            }
            return sum
        }

        fun reset() {
            for (entry in oscillators) entry.oscillator.reset()
            pressure = 0.0
            sag = 0.0
            target = 0.0
            delaySamples = 0
        }
    }

    private class OscillatorWithGain(
        val oscillator: WavetableOscillator,
        val gain: Double,
    )

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val AIR_LEVEL = 0.055
        const val DRIVE = 1.6
        const val OUTPUT_GAIN = 0.88
        const val MAX_OUTPUT = 0.95f
        const val STEEPENER_DELAY_MS = 0.8

        /** Highest chime including micro-detune and the +8 % expression bend (C2). */
        const val HIGHEST_CHIME_HZ = 493.88 * 1.004

        // Frozen chord weights (v3 calibration; C5 keeps overall loudness semantics).
        const val GAIN_B2 = 0.06
        const val GAIN_EB3 = 0.10
        const val GAIN_FS4_A = 0.16
        const val GAIN_FS4_B = 0.13
        const val GAIN_EB4_A = 0.18
        const val GAIN_EB4_B = 0.14
        const val GAIN_B4_A = 0.14
        const val GAIN_B4_B = 0.11

        // Amplitude pans from 01 §2.5.1, normalised to (g_L+g_R)/2 = 1 (mono-fold preserving).
        const val PAN_C_L = 1.0
        const val PAN_C_R = 1.0
        const val PAN_L3_L = 1.3
        const val PAN_L3_R = 0.7
        const val PAN_R2_L = 0.8
        const val PAN_R2_R = 1.2
        const val PAN_L2_L = 1.2
        const val PAN_L2_R = 0.8
        const val PAN_R3_L = 0.7
        const val PAN_R3_R = 1.3

        val DETUNE_PAIR = doubleArrayOf(0.996, 1.004)
        val PHASES = doubleArrayOf(0.03, 0.41, 0.17, 0.63, 0.29, 0.77, 0.11, 0.53)

        val CHORD_HARMONICS = doubleArrayOf(1.0, 0.58, 0.37, 0.24, 0.15, 0.10, 0.065, 0.04)
        val SUB_HARMONICS = doubleArrayOf(1.0, 0.32, 0.16, 0.08)

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
