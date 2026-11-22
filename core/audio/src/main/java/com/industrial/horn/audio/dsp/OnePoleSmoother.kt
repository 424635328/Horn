package com.industrial.horn.audio.dsp

/**
 * One-pole parameter smoother: `y[n] = y[n-1] + a * (x[n] - y[n-1])`.
 *
 * Every real-time control parameter (horn type crossfade, expression, acoustic space mix, ear
 * protection gain, custom-lab sliders, output-mode crossfade) MUST pass through a smoother like
 * this before it touches gain or coefficient math, otherwise discontinuous parameter jumps
 * produce audible zipper noise. Instances are allocation-free after construction.
 *
 * Denormal defence (01 §2.1.3): the recursion runs on `y + dc` and subtracts `dc` again, so the
 * state never parks in the exact-zero neighbourhood where JVM subnormals slow down; the DC bias
 * is 1e-20, orders of magnitude below any audible or 16-bit-relevant level.
 */
internal class OnePoleSmoother(
    sampleRate: Int,
    timeMillis: Float,
    initialValue: Float = 0f,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(timeMillis > 0f) { "timeMillis must be positive" }
    }

    private val coefficient: Float
    private var state: Float = initialValue

    init {
        val tauSamples = sampleRate * timeMillis / 1000.0
        coefficient = (1.0 - kotlin.math.exp(-1.0 / tauSamples)).toFloat().coerceIn(1e-6f, 1f)
    }

    val current: Float
        get() = state

    /** Advances one sample toward [target]; returns the smoothed value. */
    fun next(target: Float): Float {
        val dc = DspConstants.SMOOTHER_DC_BIAS.toFloat()
        state = (1f - coefficient) * (state + dc) + coefficient * target - dc
        return state
    }

    /** Snap-jumps to [value]; only for silent boundaries (reset/panic), never mid-audio. */
    fun setCurrent(value: Float) {
        state = value
    }

    fun reset(value: Float = 0f) {
        state = value
    }
}
