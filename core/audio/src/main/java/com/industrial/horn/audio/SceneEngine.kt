package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.OnePoleSmoother
import com.industrial.horn.audio.dsp.SceneSpec
import com.industrial.horn.audio.dsp.SceneSpecCatalog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Zero-allocation stereo scene engine (docs/spec/research/02): directional early reflections
 * (image-source method with ITD/ILD binauralisation), an orthogonal Hadamard FDN late tail with
 * M/S width, common (channel-independent) ground/tunnel/slab colouring and ISO 9613-1
 * atmospheric absorption, then the dry/wet mix `dry*(1-w/2) + wet*w`.
 *
 * Mono-fold safety (02 §5): the direct sound is untouched here (pure amplitude panning happens
 * in the synthesizers), early reflections carry an ITD <= 0.66 ms and <= -10 dB per reflection,
 * the FDN width is W <= 0.7 in M/S form, and every shared filter (comb, atmosphere) is
 * channel-independent.
 *
 * Scene switching crossfades equal-power over 50 ms (02 §6.3): delay-line contents are preserved
 * (never reset mid-audio), taps crossfade between old and new read offsets (never a cursor jump),
 * and scalar parameters glide through one-pole smoothers (tau = 20 ms). All buffers are
 * preallocated; the render path allocates nothing and takes no locks.
 */
internal class SceneEngine(
    private val sampleRate: Int,
) {
    private val history = FloatArray(SceneSpecCatalog.HISTORY_SAMPLES)
    private var historyIndex = 0

    private val preDelayRing = FloatArray(SceneSpecCatalog.PRE_DELAY_SAMPLES)
    private var preDelayIndex = 0

    private val combL = FloatArray(SceneSpecCatalog.COMB_SAMPLES)
    private val combR = FloatArray(SceneSpecCatalog.COMB_SAMPLES)
    private var combIndex = 0
    private var combLpL = 0.0
    private var combLpR = 0.0

    private val fdnRings = Array(FDN_LINES) { FloatArray(SceneSpecCatalog.FDN_RING_SIZES[it]) }
    private val fdnWrite = IntArray(FDN_LINES)
    private val fdnDamp = DoubleArray(FDN_LINES)
    private val fdnOut = DoubleArray(FDN_LINES)
    private val fdnMix = DoubleArray(FDN_LINES)

    private val ismLpState = Array(AcousticSpace.entries.size) { DoubleArray(2 * MAX_ISM_SOURCES) }

    // Atmospheric shelves: two first-order shelves per channel (02 §3.3).
    private val shelfX1 = DoubleArray(4)
    private val shelfY1 = DoubleArray(4)
    private val shelfB0 = DoubleArray(2)
    private val shelfB1 = DoubleArray(2)
    private val shelfA1 = DoubleArray(2)

    private val smoothFdnGain = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 0.5f)
    private val smoothFdnDamping = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 0.2f)
    private val smoothFdnWidth = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 0.5f)
    private val smoothWetMix = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 0f)
    private val smoothCombRho = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 0f)
    private val smoothG1 = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 1f)
    private val smoothG2 = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 1f)
    private val smoothCompensation = OnePoleSmoother(sampleRate, SMOOTHING_MILLIS, 1f)

    private var activeSpace = AcousticSpace.STREET
    private var fadeSpace = AcousticSpace.STREET
    private var fadeRemaining = 0
    private var fadeTotal = 1

    private var preDelayFrom = 0
    private var preDelayTo = 0
    private var combDelayFrom = 0
    private var combDelayTo = 0
    private val fdnOffsetFrom = IntArray(FDN_LINES)
    private val fdnOffsetTo = IntArray(FDN_LINES)

    init {
        hardSetSpace(AcousticSpace.STREET)
    }

    fun setSpace(space: AcousticSpace) {
        if (space == fadeSpace) return
        if (fadeRemaining > 0) activeSpace = fadeSpace
        fadeSpace = space
        if (space == activeSpace) {
            fadeRemaining = 0
        } else {
            fadeRemaining = (sampleRate * SCENE_FADE_MILLIS / 1_000f).toInt().coerceAtLeast(1)
            fadeTotal = fadeRemaining
        }
        applyReadOffsets()
    }

    /** Snap to [space] immediately; only for reset/panic boundaries, never mid-audio. */
    fun hardSetSpace(space: AcousticSpace) {
        activeSpace = space
        fadeSpace = space
        fadeRemaining = 0
        applyReadOffsets()
        snapScalars()
    }

    fun process(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        val from = SceneSpecCatalog.spec(activeSpace)
        val to = SceneSpecCatalog.spec(fadeSpace)
        if (fadeRemaining == 0 && activeSpace != fadeSpace) activeSpace = fadeSpace

        val lpStatesFrom = ismLpState[activeSpace.ordinal]
        val lpStatesTo = ismLpState[fadeSpace.ordinal]
        rebuildShelfCoefficients()

        for (frame in 0 until frameCount) {
            val left = buffer[2 * frame].toDouble()
            val right = buffer[2 * frame + 1].toDouble()
            val mid = (left + right) * 0.5

            var weightFrom = 1.0
            var weightTo = 0.0
            if (fadeRemaining > 0) {
                val progress = 1.0 - fadeRemaining.toDouble() / fadeTotal
                weightFrom = cos(PI * 0.5 * progress)
                weightTo = sin(PI * 0.5 * progress)
                fadeRemaining--
            }

            // Scalar parameters glide toward the target scene (02 §6.3).
            smoothFdnGain.next(to.fdnGain.toFloat())
            smoothFdnDamping.next(to.fdnDamping.toFloat())
            smoothFdnWidth.next(to.fdnWidth.toFloat())
            smoothWetMix.next(to.wetMix.toFloat())
            smoothCombRho.next(to.combRho.toFloat())
            smoothG1.next(to.atmosphereG1.toFloat())
            smoothG2.next(to.atmosphereG2.toFloat())
            smoothCompensation.next(to.fdnCompensation.toFloat())

            history[historyIndex] = mid.toFloat()

            // --- Early reflections (image sources or sparse far echoes) -----------------------
            var earlyL = weightFrom * ismTap(from, lpStatesFrom, channel = 0)
            var earlyR = weightFrom * ismTap(from, lpStatesFrom, channel = 1)
            if (weightTo > 0.0) {
                earlyL += weightTo * ismTap(to, lpStatesTo, channel = 0)
                earlyR += weightTo * ismTap(to, lpStatesTo, channel = 1)
            }

            // --- Late tail (orthogonal FDN) ---------------------------------------------------
            preDelayRing[preDelayIndex] = mid.toFloat()
            val sendFrom = preDelayRing[mod(preDelayIndex - preDelayFrom, preDelayRing.size)]
            val sendTo = preDelayRing[mod(preDelayIndex - preDelayTo, preDelayRing.size)]
            val send = weightFrom * sendFrom + weightTo * sendTo
            preDelayIndex++
            if (preDelayIndex >= preDelayRing.size) preDelayIndex = 0

            val damping = smoothFdnDamping.current.toDouble()
            val gain = smoothFdnGain.current.toDouble()
            val compensation = smoothCompensation.current.toDouble()
            val sendGain = FDN_SEND_GAIN * send
            for (line in 0 until FDN_LINES) {
                val ring = fdnRings[line]
                val write = fdnWrite[line]
                val readFrom = ring[mod(write - fdnOffsetFrom[line], ring.size)]
                val readTo = ring[mod(write - fdnOffsetTo[line], ring.size)]
                val lineInput = weightFrom * readFrom + weightTo * readTo
                val damped = lineInput * (1.0 - damping) + fdnDamp[line] * damping
                fdnDamp[line] = damped
                fdnOut[line] = damped
            }
            hadamard(fdnOut, fdnMix)

            var lateL = 0.0
            var lateR = 0.0
            for (line in 0 until FDN_LINES) {
                val weight = weightFrom * from.fdnWeight(line) + weightTo * to.fdnWeight(line)
                val feedback = fdnMix[line] * gain * compensation * weight
                val ring = fdnRings[line]
                ring[fdnWrite[line]] = (sendGain + feedback).coerceIn(-4.0, 4.0).toFloat()
                fdnWrite[line] = if (fdnWrite[line] + 1 >= ring.size) 0 else fdnWrite[line] + 1
                lateL += (weightFrom * from.fdnTapLeft(line) + weightTo * to.fdnTapLeft(line)) * fdnOut[line]
                lateR += (weightFrom * from.fdnTapRight(line) + weightTo * to.fdnTapRight(line)) * fdnOut[line]
            }

            // M/S width of the late tail (02 §5 C3); the wet fold keeps the mono mid intact.
            val lateMid = (lateL + lateR) * 0.5
            val lateSide = (lateL - lateR) * 0.5 * smoothFdnWidth.current.toDouble()
            var wetL = earlyL + lateMid + lateSide
            var wetR = earlyR + lateMid - lateSide

            // --- Common colouring: ground micro-comb / tunnel flutter / slab comb -------------
            combL[combIndex] = wetL.toFloat()
            combR[combIndex] = wetR.toFloat()
            val combFromL = combL[mod(combIndex - combDelayFrom, combL.size)]
            val combToL = combL[mod(combIndex - combDelayTo, combL.size)]
            val combFromR = combR[mod(combIndex - combDelayFrom, combR.size)]
            val combToR = combR[mod(combIndex - combDelayTo, combR.size)]
            combIndex++
            if (combIndex >= combL.size) combIndex = 0
            val combDelayedL = weightFrom * combFromL + weightTo * combToL
            val combDelayedR = weightFrom * combFromR + weightTo * combToR

            val combPole = lowPassPoleOf(from, to, weightFrom, weightTo)
            combLpL = (1.0 - combPole) * combDelayedL + combPole * combLpL
            combLpR = (1.0 - combPole) * combDelayedR + combPole * combLpR
            val combRho = smoothCombRho.current.toDouble()
            wetL += combRho * combLpL
            wetR += combRho * combLpR

            // --- ISO 9613-1 atmospheric absorption (identical coefficients on L/R) ------------
            wetL = shelf(0, 0, shelf(1, 0, wetL))
            wetR = shelf(0, 1, shelf(1, 1, wetR))

            // --- Dry/wet mix ------------------------------------------------------------------
            val wetMix = smoothWetMix.current.toDouble()
            val dryGain = 1.0 - wetMix * 0.5
            buffer[2 * frame] = (left * dryGain + wetL * wetMix).toFloat()
            buffer[2 * frame + 1] = (right * dryGain + wetR * wetMix).toFloat()

            historyIndex++
            if (historyIndex >= history.size) historyIndex = 0
        }
        snapDenormals()
    }

    fun reset() {
        history.fill(0f)
        historyIndex = 0
        preDelayRing.fill(0f)
        preDelayIndex = 0
        combL.fill(0f)
        combR.fill(0f)
        combIndex = 0
        combLpL = 0.0
        combLpR = 0.0
        for (line in 0 until FDN_LINES) {
            fdnRings[line].fill(0f)
            fdnWrite[line] = 0
            fdnDamp[line] = 0.0
            fdnOut[line] = 0.0
            fdnMix[line] = 0.0
        }
        for (states in ismLpState) states.fill(0.0)
        shelfX1.fill(0.0)
        shelfY1.fill(0.0)
        hardSetSpace(fadeSpace)
    }

    /** One image-source tap sum for one output channel. */
    private fun ismTap(
        spec: SceneSpec,
        lpStates: DoubleArray,
        channel: Int,
    ): Double {
        var sum = 0.0
        for (source in 0 until spec.ismCount) {
            val gain = if (channel == 0) spec.ismGainLeft[source] else spec.ismGainRight[source]
            if (gain == 0.0) continue
            val itd = spec.ismItdSamples[source]
            val nearIsLeft = spec.ismGainLeft[source] >= spec.ismGainRight[source]
            val isNearChannel = if (nearIsLeft) channel == 0 else channel == 1
            val delay =
                spec.ismDelaySamples[source] +
                    if (itd > 0 && !isNearChannel) itd.toDouble() else 0.0
            val tap = historyTap(delay)
            val stateIndex = source * 2 + channel
            val filtered = (1.0 - spec.ismLpAlpha) * tap + spec.ismLpAlpha * lpStates[stateIndex]
            lpStates[stateIndex] = filtered
            sum += gain * filtered
        }
        return sum
    }

    private fun historyTap(delaySamples: Double): Double {
        val position = historyIndex - delaySamples
        val index = Math.floor(position).toInt()
        val fraction = position - index
        val older = mod(index, history.size)
        val newer = mod(index + 1, history.size)
        return history[older] * (1.0 - fraction) + history[newer] * fraction
    }

    /** First-order shelf stage of 02 §3.3; coefficients rebuilt per block from smoothed gains. */
    private fun shelf(
        shelfStage: Int,
        channel: Int,
        x: Double,
    ): Double {
        val stateIndex = shelfStage * 2 + channel
        val y =
            shelfB0[shelfStage] * x + shelfB1[shelfStage] * shelfX1[stateIndex] -
                shelfA1[shelfStage] * shelfY1[stateIndex]
        shelfX1[stateIndex] = x
        shelfY1[stateIndex] = y
        return y
    }

    private fun lowPassPoleOf(
        from: SceneSpec,
        to: SceneSpec,
        weightFrom: Double,
        weightTo: Double,
    ): Double = weightFrom * from.combLpAlpha + weightTo * to.combLpAlpha

    private fun snapDenormals() {
        for (line in 0 until FDN_LINES) {
            if (abs(fdnDamp[line]) < DspConstants.DENORMAL_SNAP) fdnDamp[line] = 0.0
        }
        if (abs(combLpL) < DspConstants.DENORMAL_SNAP) combLpL = 0.0
        if (abs(combLpR) < DspConstants.DENORMAL_SNAP) combLpR = 0.0
        for (index in shelfX1.indices) {
            if (abs(shelfX1[index]) < DspConstants.DENORMAL_SNAP) shelfX1[index] = 0.0
            if (abs(shelfY1[index]) < DspConstants.DENORMAL_SNAP) shelfY1[index] = 0.0
        }
    }

    private fun applyReadOffsets() {
        val from = SceneSpecCatalog.spec(activeSpace)
        val to = SceneSpecCatalog.spec(fadeSpace)
        preDelayFrom = from.preDelaySamples.coerceIn(0, preDelayRing.size - 1)
        preDelayTo = to.preDelaySamples.coerceIn(0, preDelayRing.size - 1)
        combDelayFrom = from.combDelaySamples.coerceIn(1, combL.size - 1)
        combDelayTo = to.combDelaySamples.coerceIn(1, combL.size - 1)
        for (line in 0 until FDN_LINES) {
            fdnOffsetFrom[line] = from.fdnReadOffset(line)
            fdnOffsetTo[line] = to.fdnReadOffset(line)
        }
    }

    private fun snapScalars() {
        val to = SceneSpecCatalog.spec(fadeSpace)
        smoothFdnGain.setCurrent(to.fdnGain.toFloat())
        smoothFdnDamping.setCurrent(to.fdnDamping.toFloat())
        smoothFdnWidth.setCurrent(to.fdnWidth.toFloat())
        smoothWetMix.setCurrent(to.wetMix.toFloat())
        smoothCombRho.setCurrent(to.combRho.toFloat())
        smoothG1.setCurrent(to.atmosphereG1.toFloat())
        smoothG2.setCurrent(to.atmosphereG2.toFloat())
        smoothCompensation.setCurrent(to.fdnCompensation.toFloat())
        rebuildShelfCoefficients()
    }

    private fun rebuildShelfCoefficients() {
        for (stage in 0..1) {
            val gain = if (stage == 0) smoothG1.current.toDouble() else smoothG2.current.toDouble()
            val corner = if (stage == 0) SHELF1_CORNER_HZ else SHELF2_CORNER_HZ
            val fs = sampleRate.toDouble()
            val w0p = 2.0 * fs * tan(PI * corner / fs)
            val k = 2.0 * fs
            shelfB0[stage] = (gain * k + w0p) / (k + w0p)
            shelfB1[stage] = (w0p - gain * k) / (k + w0p)
            shelfA1[stage] = (w0p - k) / (k + w0p)
        }
    }

    private fun hadamard(
        input: DoubleArray,
        output: DoubleArray,
    ) {
        val t0 = input[0] + input[2]
        val t1 = input[0] - input[2]
        val t2 = input[1] + input[3]
        val t3 = input[1] - input[3]
        val h0 = t0 + t2
        val h1 = t1 + t3
        val h2 = t0 - t2
        val h3 = t1 - t3
        val u0 = input[4] + input[6]
        val u1 = input[4] - input[6]
        val u2 = input[5] + input[7]
        val u3 = input[5] - input[7]
        val k0 = u0 + u2
        val k1 = u1 + u3
        val k2 = u0 - u2
        val k3 = u1 - u3
        output[0] = (h0 + k0) * BUTTERFLY_SCALE
        output[1] = (h1 + k1) * BUTTERFLY_SCALE
        output[2] = (h2 + k2) * BUTTERFLY_SCALE
        output[3] = (h3 + k3) * BUTTERFLY_SCALE
        output[4] = (h0 - k0) * BUTTERFLY_SCALE
        output[5] = (h1 - k1) * BUTTERFLY_SCALE
        output[6] = (h2 - k2) * BUTTERFLY_SCALE
        output[7] = (h3 - k3) * BUTTERFLY_SCALE
    }

    private fun mod(
        index: Int,
        size: Int,
    ): Int = Math.floorMod(index, size)

    private companion object {
        const val FDN_LINES = 8
        const val MAX_ISM_SOURCES = 18
        const val SMOOTHING_MILLIS = 20f
        const val SCENE_FADE_MILLIS = 50f
        const val SHELF1_CORNER_HZ = 2_000.0
        const val SHELF2_CORNER_HZ = 8_000.0

        /**
         * Wet-send coupling of the FDN input, a normalisation constant of the topology (the
         * tap matrix fixes the output side). Calibrated so every scene keeps an audible tail
         * after the ISO 9613-1 atmosphere stage (02 §5 acceptance: open-road tail audible).
         */
        const val FDN_SEND_GAIN = 4.0

        val BUTTERFLY_SCALE = 1.0 / sqrt(8.0)
    }
}

/** Per-scene FDN accessors: line weights, normalised Hadamard taps and ring read offsets. */
private fun SceneSpec.fdnWeight(line: Int): Double = if (line < fdnActiveLines) 1.0 else 0.0

private fun SceneSpec.fdnTapLeft(line: Int): Double =
    when {
        line >= fdnActiveLines -> 0.0
        fdnActiveLines == 8 -> FDN8_TAP
        else -> FDN4_TAP
    }

private fun SceneSpec.fdnTapRight(line: Int): Double =
    when {
        line >= fdnActiveLines -> 0.0
        fdnActiveLines == 8 -> if (line < 4) FDN8_TAP else -FDN8_TAP
        else -> if (line < 2) FDN4_TAP else -FDN4_TAP
    }

/** Ring offset realising this scene's delay on [line]; the shared ring is sized by the catalog. */
private fun SceneSpec.fdnReadOffset(line: Int): Int {
    val size = SceneSpecCatalog.FDN_RING_SIZES[line]
    val delay = if (line < fdnActiveLines) fdnDelays[line] else size - 1
    return delay.coerceIn(1, size - 1)
}

/**
 * Feedback compensation for M=4 scenes: the 8-point butterfly would leak half of the four-line
 * subsystem's energy into the zero-weighted lines, so the loop gain is scaled by sqrt(2) to keep
 * the orthogonal gain (and therefore the RT60 formula of 02 §3.2) exact.
 */
internal val SceneSpec.fdnCompensation: Double
    get() = if (fdnActiveLines == 8) 1.0 else sqrt(2.0)

private const val FDN8_TAP = 0.35355339059327373 // 1/sqrt(8), row of H8/sqrt(8)
private const val FDN4_TAP = 0.5 // 1/sqrt(4), row of H4/sqrt(4)
