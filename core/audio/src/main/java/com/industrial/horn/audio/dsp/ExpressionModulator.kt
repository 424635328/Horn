package com.industrial.horn.audio.dsp

import kotlin.math.max

/**
 * Per-sample smoothed expression, five channels wide (01 §2.3.3).
 *
 * Expression is intentionally continuous: pitch bends by up to +/-8 %, saturation and air noise
 * follow on the slower smoothing channel, while drive and brassiness use the faster 5 ms channel
 * so brightness never lags behind pitch. All constants come from [DspConstants] (C6 ruling).
 */
internal class ExpressionModulator(
    sampleRate: Int = DspConstants.SAMPLE_RATE,
) {
    private val slow = OnePoleSmoother(sampleRate, DspConstants.EXPR_TAU_MILLIS, 0f)
    private val fast = OnePoleSmoother(sampleRate, DspConstants.EXPR_FAST_TAU_MILLIS, 0f)

    private var target = 0f

    fun setTarget(value: Float) {
        target = value.coerceIn(-1f, 1f)
    }

    fun advance() {
        slow.next(target)
        fast.next(target)
    }

    val pitchScale: Double
        get() = 1.0 + slow.current * DspConstants.EXPR_PITCH_BEND

    val noiseScale: Double
        get() = 1.0 + slow.current * DspConstants.EXPR_NOISE_RANGE

    val driveScale: Double
        get() = 1.0 + fast.current * DspConstants.EXPR_DRIVE_RANGE

    /** Scheme (1) steepening depth: `beta = base + range * max(0, e)`, clamped to 0.35. */
    val brassBeta: Double
        get() =
            (
                DspConstants.EXPR_BRASS_BETA_BASE +
                    DspConstants.EXPR_BRASS_BETA_RANGE * max(0f, fast.current)
            ).coerceAtMost(0.35)

    /** Scheme (2) asymmetry: `alpha = base + range * max(0, e)`, clamped to 0.25. */
    val brassAsym: Double
        get() =
            (
                DspConstants.EXPR_BRASS_ASYM_BASE +
                    DspConstants.EXPR_BRASS_ASYM_RANGE * max(0f, fast.current)
            ).coerceAtMost(0.25)

    fun reset() {
        target = 0f
        slow.reset()
        fast.reset()
    }
}
