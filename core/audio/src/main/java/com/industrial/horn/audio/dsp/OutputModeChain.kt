package com.industrial.horn.audio.dsp

import com.industrial.horn.audio.OutputMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Device-adaptive output mode chain (03 §1/§2): two parallel branches over the scene-engine
 * stereo signal, crossfaded equal-power over 30 ms when the mode changes (20-50 ms window,
 * 03 §2.5).
 *
 *  - [OutputMode.SPEAKER_VBS]: mono-core Virtual Bass System for micro speakers — protective
 *    90 Hz high-pass, 80-280 Hz low-band extraction, rectification-dominant composite NLD
 *    (`w_e(|x|-mean) + w_o x|x|`), 250-900 Hz linear-phase band limit with 31-sample dry
 *    alignment, injection with `g_mix`, then a light `e = 0.15` stereo re-widening whose fold
 *    `(L'+R')/2` is exactly the mono core (02 §5 C1-C4 safe).
 *  - [OutputMode.HEADPHONE_FULL_RANGE]: full low end, M/S width `w = 0.9` on the side channel
 *    with a 2 kHz ILD tilt and a 28-sample (<= 0.6 ms) ITD micro-delay on S only;
 *    `L+R = 2M` is preserved, so mono folding stays clean.
 *
 * Both branches run continuously (except across a settled non-fade state) so a mode switch never
 * lands on stale filter state. Everything is preallocated; per-sample work is allocation-free.
 */
internal class OutputModeChain(
    private val sampleRate: Int,
) {
    // --- Speaker VBS branch (mono core path) -------------------------------------------------
    private val hp90Biquad = BiquadFilter.highPass(sampleRate, DspConstants.VBS_HP_FC, 0.5)
    private val hp90Pole = OnePoleHighPass(sampleRate, DspConstants.VBS_HP_FC)
    private val extractHigh = BiquadFilter.highPass(sampleRate, 80.0, 0.707)
    private val extractLow = BiquadFilter.lowPass(sampleRate, 280.0, 0.707)
    private val nldDcBlock = OnePoleHighPass(sampleRate, 20.0)
    private val nldMean = OnePoleSmoother(sampleRate, 19.9f, 0f) // mu ≈ 1 - exp(-2*pi*8/fs)
    private val firHistory = FloatArray(FIR_TAPS)
    private val firTaps = buildBandLimitFir()
    private var firIndex = 0
    private val speakerDryDelay = FloatArray(FIR_GROUP_DELAY + 1)
    private var speakerDryIndex = 0
    private val speakerSideDelay = FloatArray(FIR_GROUP_DELAY + 1)
    private var speakerSideIndex = 0

    // --- Headphone width branch ---------------------------------------------------------------
    private val headphoneSideDelay = FloatArray(SIDE_DELAY_SAMPLES + 1)
    private var headphoneSideIndex = 0
    private val sideTilt = BiquadFilter.highShelf(sampleRate, 2_000.0, 0.707, SIDE_TILT_DB)

    // --- Control state -------------------------------------------------------------------------
    private val injectionSmoother = OnePoleSmoother(sampleRate, 30f, 0f)

    @Volatile
    private var injectionTarget = 0f

    @Volatile
    private var targetMode: OutputMode = OutputMode.SPEAKER_VBS

    private var renderedMode = OutputMode.SPEAKER_VBS
    private var fadeRemaining = 0
    private var fadeTotal = 1

    // Branch scratch fields: written by the branch helpers, consumed immediately (no allocation).
    private var branchL = 0.0
    private var branchR = 0.0

    fun setMode(mode: OutputMode) {
        targetMode = mode
    }

    /** Static VBS injection per voice (03 §2.1.6): 0 for non-bass voices, 1 for full injection. */
    fun setInjection(injection: Float) {
        injectionTarget = injection.coerceIn(0f, 1f)
    }

    fun process(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        val requested = targetMode
        if (requested != renderedMode && fadeRemaining == 0) {
            fadeRemaining = (sampleRate * MODE_FADE_MILLIS / 1_000f).toInt().coerceAtLeast(1)
            fadeTotal = fadeRemaining
        }
        for (frame in 0 until frameCount) {
            val left = buffer[2 * frame].toDouble()
            val right = buffer[2 * frame + 1].toDouble()
            val mid = (left + right) * 0.5
            val side = (left - right) * 0.5

            var outL: Double
            var outR: Double
            if (fadeRemaining > 0) {
                val fadeFromSpeaker = renderedMode == OutputMode.SPEAKER_VBS
                speakerBranch(mid, side)
                val speakerOutL = branchL
                val speakerOutR = branchR
                headphoneBranch(mid, side)
                val headphoneOutL = branchL
                val headphoneOutR = branchR

                val progress = 1.0 - fadeRemaining.toDouble() / fadeTotal
                val gainNew = sin(PI * 0.5 * progress)
                val gainOld = cos(PI * 0.5 * progress)
                val oldL = if (fadeFromSpeaker) speakerOutL else headphoneOutL
                val oldR = if (fadeFromSpeaker) speakerOutR else headphoneOutR
                val newL = if (fadeFromSpeaker) headphoneOutL else speakerOutL
                val newR = if (fadeFromSpeaker) headphoneOutR else speakerOutR
                outL = gainOld * oldL + gainNew * newL
                outR = gainOld * oldR + gainNew * newR
                fadeRemaining--
                if (fadeRemaining == 0) renderedMode = requested
            } else if (renderedMode == OutputMode.SPEAKER_VBS) {
                speakerBranch(mid, side)
                outL = branchL
                outR = branchR
            } else {
                headphoneBranch(mid, side)
                outL = branchL
                outR = branchR
            }

            buffer[2 * frame] = outL.toFloat()
            buffer[2 * frame + 1] = outR.toFloat()
        }
        snapDenormals()
    }

    private fun speakerBranch(
        mid: Double,
        side: Double,
    ) {
        // Protective 90 Hz Butterworth (3rd order: biquad Q = 0.5 + real pole).
        val protected = hp90Pole.process(hp90Biquad.process(mid))

        // 80-280 Hz low-band extraction for the NLD, pre-limited to |x| <= 0.95 (03 §2.1.3).
        val band = extractLow.process(extractHigh.process(protected)).coerceIn(-NLD_INPUT_LIMIT, NLD_INPUT_LIMIT)

        // Rectification-dominant composite NLD (03 §2.1.3).
        val magnitude = abs(band)
        val mean = nldMean.next(magnitude.toFloat()).toDouble()
        val raw = DspConstants.VBS_WE * (magnitude - mean) + DspConstants.VBS_WO * band * magnitude
        val harmonics = nldDcBlock.process(raw)

        firHistory[firIndex] = harmonics.toFloat()
        var aligned = 0.0
        for (tap in 0 until FIR_TAPS) {
            val index = Math.floorMod(firIndex - tap, FIR_TAPS)
            aligned += firTaps[tap] * firHistory[index]
        }
        firIndex++
        if (firIndex >= FIR_TAPS) firIndex = 0

        speakerDryDelay[speakerDryIndex] = protected.toFloat()
        val dryRead = Math.floorMod(speakerDryIndex - FIR_GROUP_DELAY, speakerDryDelay.size)
        val dryAligned = speakerDryDelay[dryRead].toDouble()
        speakerDryIndex++
        if (speakerDryIndex >= speakerDryDelay.size) speakerDryIndex = 0

        speakerSideDelay[speakerSideIndex] = side.toFloat()
        val sideRead = Math.floorMod(speakerSideIndex - FIR_GROUP_DELAY, speakerSideDelay.size)
        val sideAligned = speakerSideDelay[sideRead].toDouble()
        speakerSideIndex++
        if (speakerSideIndex >= speakerSideDelay.size) speakerSideIndex = 0

        val injection = injectionSmoother.next(injectionTarget).toDouble()
        val mix = DspConstants.VBS_GMIX * injection
        val core = dryAligned + mix * aligned
        val spread = SIDE_SPREAD * sideAligned
        branchL = core + spread
        branchR = core - spread
    }

    private fun headphoneBranch(
        mid: Double,
        side: Double,
    ) {
        headphoneSideDelay[headphoneSideIndex] = side.toFloat()
        val sideRead = Math.floorMod(headphoneSideIndex - SIDE_DELAY_SAMPLES, headphoneSideDelay.size)
        val delayedSide = headphoneSideDelay[sideRead].toDouble()
        headphoneSideIndex++
        if (headphoneSideIndex >= headphoneSideDelay.size) headphoneSideIndex = 0

        val widened = SIDE_WIDTH * sideTilt.process(delayedSide)
        branchL = mid + widened
        branchR = mid - widened
    }

    fun reset() {
        hp90Biquad.reset()
        hp90Pole.reset()
        extractHigh.reset()
        extractLow.reset()
        nldDcBlock.reset()
        nldMean.reset()
        firHistory.fill(0f)
        firIndex = 0
        speakerDryDelay.fill(0f)
        speakerDryIndex = 0
        speakerSideDelay.fill(0f)
        speakerSideIndex = 0
        headphoneSideDelay.fill(0f)
        headphoneSideIndex = 0
        sideTilt.reset()
        injectionSmoother.reset()
        injectionSmoother.setCurrent(injectionTarget)
        renderedMode = targetMode
        fadeRemaining = 0
    }

    private fun snapDenormals() {
        if (abs(nldMean.current) < DspConstants.DENORMAL_SNAP.toFloat()) nldMean.reset()
        if (abs(injectionSmoother.current) < DspConstants.DENORMAL_SNAP.toFloat()) {
            injectionSmoother.reset()
            injectionSmoother.setCurrent(injectionTarget)
        }
    }

    /**
     * 63-tap Blackman-windowed linear-phase band-pass (250-900 Hz), built from the windowed-sinc
     * formula (03 §2.1.5) so no coefficient is hand-copied; peak gain normalised to 0 dB.
     */
    private fun buildBandLimitFir(): DoubleArray {
        val taps = DoubleArray(FIR_TAPS)
        val alpha = (FIR_TAPS - 1) * 0.5
        val omega1 = 2.0 * PI * 250.0 / sampleRate
        val omega2 = 2.0 * PI * 900.0 / sampleRate
        for (k in 0 until FIR_TAPS) {
            val m = k - alpha
            val ideal =
                if (abs(m) < 1e-12) {
                    (omega2 - omega1) / PI
                } else {
                    (sin(omega2 * m) - sin(omega1 * m)) / (PI * m)
                }
            val window =
                0.42 - 0.5 * cos(2.0 * PI * k / (FIR_TAPS - 1)) +
                    0.08 * cos(4.0 * PI * k / (FIR_TAPS - 1))
            taps[k] = ideal * window
        }
        var response = 0.0
        for (k in 0 until FIR_TAPS) {
            response += taps[k] * cos(2.0 * PI * 474.3 / sampleRate * (k - alpha))
        }
        val scale = if (abs(response) > 1e-9) 1.0 / response else 1.0
        for (k in 0 until FIR_TAPS) taps[k] *= scale
        return taps
    }

    private class OnePoleHighPass(
        sampleRate: Int,
        cutoffHz: Double,
    ) {
        private val coefficient = exp(-2.0 * PI * cutoffHz / sampleRate)
        private var x1 = 0.0
        private var y1 = 0.0

        fun process(input: Double): Double {
            val output = coefficient * (y1 + input - x1)
            x1 = input
            y1 = output
            return output
        }

        fun reset() {
            x1 = 0.0
            y1 = 0.0
        }
    }

    private companion object {
        const val FIR_TAPS = 63
        const val FIR_GROUP_DELAY = 31
        const val SIDE_DELAY_SAMPLES = 28
        const val SIDE_WIDTH = 0.9
        const val SIDE_SPREAD = 0.15
        const val SIDE_TILT_DB = 2.0
        const val MODE_FADE_MILLIS = 30f
        const val NLD_INPUT_LIMIT = 0.95
    }
}
