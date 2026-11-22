package com.industrial.horn.audio.dsp

import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.HornType
import com.industrial.horn.audio.OutputMode
import kotlin.math.pow

/**
 * Feed-forward loudness alignment of 03 §4: `G = makeup * g_scene * g_mode * (protection ? 0.501
 * : 1) * 0.9`. The table below is the frozen `g_scene x g_mode` correction in dB for the
 * 5 voices x 4 scenes x 2 modes grid; the makeup gains live in [DspConstants] (C5 keeps them).
 *
 * GARAGE has no row in the frozen 03 §4 table (it predates the fourth scene); its values are the
 * midpoint of the TUNNEL/OPEN_ROAD columns, which matches its wetMix sitting exactly between the
 * two (0.30 vs 0.38/0.22). The C++ mirror MUST use the same derivation.
 */
internal object LoudnessTable {
    // SPEAKER_VBS corrections [dB]: rows = CAR, TRAIN, EBIKE, MOTORCYCLE, CUSTOM.
    private val SPEAKER =
        arrayOf(
            doubleArrayOf(-0.5, -1.1, -0.8, -0.95),
            doubleArrayOf(-1.2, -1.8, -1.5, -1.65),
            doubleArrayOf(-0.5, -1.1, -0.8, -0.95),
            doubleArrayOf(-0.5, -1.1, -0.8, -0.95),
            doubleArrayOf(-0.9, -1.5, -1.2, -1.35),
        )

    // HEADPHONE_FULL_RANGE corrections [dB].
    private val HEADPHONE =
        arrayOf(
            doubleArrayOf(-0.2, -0.8, -0.5, -0.65),
            doubleArrayOf(-0.2, -0.8, -0.5, -0.65),
            doubleArrayOf(-0.2, -0.8, -0.5, -0.65),
            doubleArrayOf(-0.2, -0.8, -0.5, -0.65),
            doubleArrayOf(-0.2, -0.8, -0.5, -0.65),
        )

    /** `g_scene * g_mode` as a linear factor for one (voice, scene, mode) combination. */
    fun correction(
        type: HornType,
        space: AcousticSpace,
        mode: OutputMode,
    ): Float {
        val table = if (mode == OutputMode.SPEAKER_VBS) SPEAKER else HEADPHONE
        val db = table[type.ordinal][space.ordinal]
        return 10.0.pow(db / 20.0).toFloat()
    }

    /** The full feed-forward gain `G` of 03 §2.2, before the protection stage. */
    fun totalGain(
        type: HornType,
        space: AcousticSpace,
        mode: OutputMode,
        protectionActive: Boolean,
    ): Float {
        val makeup =
            when (type) {
                HornType.CAR -> DspConstants.MAKEUP_CAR
                HornType.TRAIN -> DspConstants.MAKEUP_TRAIN
                HornType.EBIKE -> DspConstants.MAKEUP_EBIKE
                HornType.MOTORCYCLE -> DspConstants.MAKEUP_MOTORCYCLE
                HornType.CUSTOM -> DspConstants.MAKEUP_CUSTOM
            }
        val protection = if (protectionActive) DspConstants.PROTECTION_GAIN else 1f
        return makeup * correction(type, space, mode) * protection * DspConstants.MASTER_GAIN
    }

    /**
     * Static VBS injection per voice (03 §2.1.6): TRAIN and low-CUSTOM carry content inside the
     * 80-280 Hz extraction band and get full injection; the other voices get none.
     */
    fun vbsInjection(
        type: HornType,
        customPrimaryHz: Float,
    ): Float =
        when (type) {
            HornType.TRAIN -> 1f
            HornType.CUSTOM -> if (customPrimaryHz < 280f) 1f else 0f
            else -> 0f
        }
}
