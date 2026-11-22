package com.industrial.horn.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen-constant guard for the shared constant table (contract 00 §3). Every value here is
 * mirrored 1:1 by `dsp_constants.h`; a drift in either direction is a contract revision.
 */
class DspConstantsTest {
    @Test
    fun makeupAndMasterConstantsMatchTheFrozenContract() {
        assertEquals(0.47f, DspConstants.MAKEUP_CAR, 0f)
        assertEquals(0.52f, DspConstants.MAKEUP_TRAIN, 0f)
        assertEquals(0.18f, DspConstants.MAKEUP_EBIKE, 0f)
        assertEquals(0.30f, DspConstants.MAKEUP_MOTORCYCLE, 0f)
        assertEquals(0.22f, DspConstants.MAKEUP_CUSTOM, 0f)
        assertEquals(0.9f, DspConstants.MASTER_GAIN, 0f)
        assertEquals(0.501f, DspConstants.PROTECTION_GAIN, 0f)
        assertEquals(0.98f, DspConstants.HARD_CEILING, 0f)
        assertEquals(1.0, DspConstants.LIMITER_DRIVE, 0.0)
    }

    @Test
    fun expressionAndTransConstantsMatchTheFrozenContract() {
        assertEquals(0.08, DspConstants.EXPR_PITCH_BEND, 0.0)
        assertEquals(0.35, DspConstants.EXPR_DRIVE_RANGE, 0.0)
        assertEquals(0.6, DspConstants.EXPR_NOISE_RANGE, 0.0)
        assertEquals(0.05, DspConstants.EXPR_BRASS_BETA_BASE, 0.0)
        assertEquals(0.30, DspConstants.EXPR_BRASS_BETA_RANGE, 0.0)
        assertEquals(0.10, DspConstants.EXPR_BRASS_ASYM_BASE, 0.0)
        assertEquals(0.15, DspConstants.EXPR_BRASS_ASYM_RANGE, 0.0)
        assertEquals(0.0025f, DspConstants.EXPR_SMOOTHING, 0f)
        assertEquals(-0.018, DspConstants.TRAIN_PITCH_SAG_ONSET, 0.0)
        assertEquals(-0.019, DspConstants.TRAIN_PITCH_SAG_MIN, 0.0)
        assertEquals(0.015, DspConstants.TRAIN_PITCH_SAG_MAX, 0.0)
        assertTrue(DspConstants.TRAIN_PITCH_KAPPA in 0.005..0.015)
    }

    @Test
    fun vbsConstantsMatchTheFrozenContract() {
        assertEquals(0.70, DspConstants.VBS_WE, 0.0)
        assertEquals(0.25, DspConstants.VBS_WO, 0.0)
        assertEquals(0.45, DspConstants.VBS_GMIX, 0.0)
        assertEquals(90.0, DspConstants.VBS_HP_FC, 0.0)
        assertEquals(1e-12, DspConstants.CORR_SILENCE_EPS, 0.0)
    }

    @Test
    fun harmonicLimitFollowsTheDynamicBandLimitFormula() {
        // C2: N_max = floor(0.45*fs/(f0*1.08)) — the e-bike defect case must clamp to 7.
        assertEquals(7, DspConstants.harmonicLimit(48_000, 2_785.0))
        // The custom 3 kHz x 12 harmonic defect clamps to 6.
        assertEquals(6, DspConstants.harmonicLimit(48_000, 3_000.0))
        // Safe voices keep their stacks.
        assertEquals(40, DspConstants.harmonicLimit(48_000, 500.0))
    }
}
