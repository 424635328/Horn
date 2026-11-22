package com.industrial.horn.audio.dsp

/**
 * Single source of truth for every frozen DSP constant of the stereo pipeline contract
 * (docs/spec/research/00-stereo-pipeline-contract.md §3). Every Kotlin consumer —
 * synths, expression modulator, scene engine, output-mode chain, output stage — MUST read
 * its values from this object instead of re-declaring literals.
 *
 * The C++ mirror `dsp_constants.h` MUST be kept value-identical (C3/C6 rulings); QA compares
 * both ends sample-by-sample with a 1e-5 tolerance. Values below are frozen; changing one is a
 * contract revision, not a code change.
 */
object DspConstants {
    // ---------------------------------------------------------------------------------------------
    // §3 equal-loudness makeup gains (ADR-0004). Voice calibration; MUST NOT drift silently.
    // ---------------------------------------------------------------------------------------------
    const val MAKEUP_CAR = 0.47f
    const val MAKEUP_TRAIN = 0.52f
    const val MAKEUP_EBIKE = 0.18f
    const val MAKEUP_MOTORCYCLE = 0.30f
    const val MAKEUP_CUSTOM = 0.22f

    /** Master gain of the feed-forward loudness chain, `G = makeup x g_scene x g_mode x p x 0.9`. */
    const val MASTER_GAIN = 0.9f

    /** Ear protection gain, about -6 dB, applied only when the safety policy demands it. */
    const val PROTECTION_GAIN = 0.501f

    /** The one and only hard output boundary (invariant I4). */
    const val HARD_CEILING = 0.98f

    /** Single soft-saturation drive of the merged output stage (`tanh(d*x)/tanh(d)`). */
    const val LIMITER_DRIVE = 1.0

    // ---------------------------------------------------------------------------------------------
    // §3 expression channels (01 §2.3.3 five-channel mapping).
    // ---------------------------------------------------------------------------------------------
    const val EXPR_PITCH_BEND = 0.08
    const val EXPR_DRIVE_RANGE = 0.35
    const val EXPR_NOISE_RANGE = 0.6
    const val EXPR_BRASS_BETA_BASE = 0.05
    const val EXPR_BRASS_BETA_RANGE = 0.30
    const val EXPR_BRASS_ASYM_BASE = 0.10
    const val EXPR_BRASS_ASYM_RANGE = 0.15

    /** Expression smoothing coefficient, about tau = 8.32 ms at 48 kHz (kept from v3). */
    const val EXPR_SMOOTHING = 0.0025f

    /** Faster smoothing for drive/brassiness channels, tau = 5 ms (01 §2.3.3). */
    const val EXPR_FAST_TAU_MILLIS = 5f

    /** Slower expression smoothing time constant matching [EXPR_SMOOTHING] at 48 kHz. */
    const val EXPR_TAU_MILLIS = 8.32f

    // ---------------------------------------------------------------------------------------------
    // §3 train pneumatic pitch sag (C4 ruling: merged into the valve ODE, no BEND_START any more).
    // ---------------------------------------------------------------------------------------------
    const val TRAIN_PITCH_SAG_ONSET = -0.018
    const val TRAIN_PITCH_SAG_MIN = -0.019
    const val TRAIN_PITCH_SAG_MAX = 0.015
    const val TRAIN_PITCH_SAG_TAU_MILLIS = 45f

    /** Pressure-to-pitch coupling `kappa in [0.005, 0.015]` (01 §2.5.3). */
    const val TRAIN_PITCH_KAPPA = 0.012

    // ---------------------------------------------------------------------------------------------
    // §3 Virtual Bass System (03 §2.1, "rectification-dominant composite NLD" selection).
    // ---------------------------------------------------------------------------------------------
    const val VBS_WE = 0.70
    const val VBS_WO = 0.25
    const val VBS_GMIX = 0.45
    const val VBS_HP_FC = 90.0

    /** Correlation silence threshold (03 telemetry contract). */
    const val CORR_SILENCE_EPS = 1e-12

    // ---------------------------------------------------------------------------------------------
    // Buffer / thread contract (00 §2).
    // ---------------------------------------------------------------------------------------------
    const val SAMPLE_RATE = 48_000
    const val BLOCK_FRAMES = 128

    /** Block-level denormal snap: states with `|x| < 1e-20` collapse to zero (00 §2). */
    const val DENORMAL_SNAP = 1e-20

    /** DC bias keeping recursive states out of the exact-zero neighbourhood (00 §2). */
    const val SMOOTHER_DC_BIAS = 1e-20

    /**
     * Dynamic band limit for harmonic wavetables: `N_max = floor(0.45 * fs / f_max)` where
     * `f_max = f0_max * pitchScale_max` includes the +8 % expression bend (C2 ruling).
     */
    fun harmonicLimit(
        sampleRate: Int,
        maxFundamentalHz: Double,
    ): Int {
        val fMax = maxFundamentalHz * (1.0 + EXPR_PITCH_BEND)
        if (fMax <= 0.0) return 1
        val limit = (0.45 * sampleRate / fMax).toInt()
        return if (limit < 1) 1 else limit
    }
}
