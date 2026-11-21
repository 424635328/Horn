package com.industrial.autohorn.audio.dsp

/**
 * Per-sample smoothed expression. Expression is intentionally continuous: pitch bends by up to
 * +/-8%, saturation and air noise follow with a slower coefficient so vertical drags never zipper.
 */
internal class ExpressionModulator {
    private var target = 0f
    private var current = 0f

    fun setTarget(value: Float) {
        target = value.coerceIn(-1f, 1f)
    }

    fun advance() {
        current += (target - current) * SMOOTHING
    }

    val pitchScale: Double
        get() = 1.0 + current * MAX_PITCH_BEND

    val driveScale: Double
        get() = 1.0 + current * DRIVE_RANGE

    val noiseScale: Double
        get() = 1.0 + current * NOISE_RANGE

    fun reset() {
        target = 0f
        current = 0f
    }

    private companion object {
        const val SMOOTHING = 0.0025f
        const val MAX_PITCH_BEND = 0.08
        const val DRIVE_RANGE = 0.35
        const val NOISE_RANGE = 0.6
    }
}
