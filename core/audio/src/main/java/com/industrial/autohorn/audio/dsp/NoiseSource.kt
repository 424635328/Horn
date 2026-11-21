package com.industrial.autohorn.audio.dsp

/**
 * Deterministic white-noise source. A fixed seed makes DSP regression tests reproducible and
 * avoids `Random` allocation on the audio thread.
 */
internal class NoiseSource(
    seed: Int = DEFAULT_SEED,
) {
    private val initialSeed = seed
    private var state = seed

    fun nextWhite(): Double {
        state = state * LCG_MULTIPLIER + LCG_INCREMENT
        return ((state ushr 8) and 0xFFFF) / 32_768.0 - 1.0
    }

    fun reset() {
        state = initialSeed
    }

    private companion object {
        const val DEFAULT_SEED = 0x4D595DF4
        const val LCG_MULTIPLIER = 1_664_525
        const val LCG_INCREMENT = 1_013_904_223
    }
}
