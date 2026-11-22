package com.industrial.horn.audio

/**
 * Streaming stereo waveform source. Implementations write normalized floats in [-1, 1] and must
 * never allocate inside [render] because it is called on the real-time audio thread.
 */
interface HornSynthesizer {
    val sampleRate: Int

    /**
     * Fills the first `2 * frameCount` floats of [buffer] with the next interleaved stereo block
     * (`buffer[2i]` = left, `buffer[2i+1]` = right, 48 kHz). The rest of the buffer must be left
     * untouched. Thread contract unchanged: zero allocation, zero locks, zero logging.
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

/**
 * Optional note-gate extension: physical micro-transients that need note boundaries (relay clicks,
 * pneumatic valve ODE, DC switch clicks, damped release rings) are driven from here. The frozen
 * [HornSynthesizer] contract carries no gate, so the renderer forwards noteOn/noteOff only to
 * voices that implement this interface. The C++ mirror owns note state natively.
 */
interface NoteAwareSynthesizer : HornSynthesizer {
    /** True at note start, false when the master envelope releases. */
    fun setNoteActive(active: Boolean)
}
