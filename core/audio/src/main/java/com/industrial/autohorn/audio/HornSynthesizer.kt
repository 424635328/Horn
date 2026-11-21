package com.industrial.autohorn.audio

/**
 * Streaming waveform source. Implementations write normalized floats in [-1, 1] and must never
 * allocate inside [render] because it is called on the real-time audio thread.
 */
interface HornSynthesizer {
    val sampleRate: Int

    /**
     * Fills the first [frameCount] samples of [buffer] with the next mono block.
     * The rest of the buffer must be left untouched.
     */
    fun render(
        buffer: FloatArray,
        frameCount: Int,
    )

    /**
     * Continuous expression in [-1, 1]: positive bends pitch upward and opens the harmonic
     * brightness; negative does the opposite. Implementations smooth internally per sample.
     */
    fun setExpression(expression: Float) = Unit

    /** Resets oscillator and filter state without allocating. */
    fun reset()
}
