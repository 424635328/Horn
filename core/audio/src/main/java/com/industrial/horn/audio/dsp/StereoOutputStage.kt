package com.industrial.horn.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * The single output stage of the stereo pipeline (00 §1 C8, 03 §2.4): true-peak detection with
 * 4x polyphase interpolation, a look-ahead smoothed soft limiter (T = 0.98), one soft-saturation
 * `tanh(d*x)/tanh(d)` with `d = 1.0`, and the `+/-0.98` hard boundary as the one and only exit
 * (invariant I4). The old two-stage `PcmRenderer` + `writeLimitedPcm` tanh chain is merged here;
 * PCM16 conversion now exists only in the telemetry mirror.
 *
 * The gain computer is stereo-linked (one gain from both channels' true peaks) so the stereo
 * image never shifts under limiting. Everything is preallocated; processing is per-sample and
 * allocation-free.
 */
internal class StereoOutputStage {
    private val tpHistoryL = FloatArray(TAPS)
    private val tpHistoryR = FloatArray(TAPS)
    private val lookAheadL = FloatArray(LOOKAHEAD_SAMPLES + 1)
    private val lookAheadR = FloatArray(LOOKAHEAD_SAMPLES + 1)
    private var historyIndex = 0
    private var lookAheadIndex = 0
    private var gain = 1.0

    /**
     * Limits one interleaved stereo block in place. [frameCount] frames occupy the first
     * `2*frameCount` floats; the rest of the buffer is untouched.
     */
    fun process(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        for (frame in 0 until frameCount) {
            val left = buffer[2 * frame].toDouble()
            val right = buffer[2 * frame + 1].toDouble()

            tpHistoryL[historyIndex] = left.toFloat()
            tpHistoryR[historyIndex] = right.toFloat()

            val tp =
                maxOf(
                    truePeak(tpHistoryL, historyIndex),
                    truePeak(tpHistoryR, historyIndex),
                    abs(left),
                    abs(right),
                )

            val target = if (tp > GAIN_FLOOR) minOf(1.0, THRESHOLD / tp) else 1.0
            gain =
                if (target < gain) {
                    ATTACK_COEFFICIENT * gain + (1.0 - ATTACK_COEFFICIENT) * target
                } else {
                    RELEASE_COEFFICIENT * gain + (1.0 - RELEASE_COEFFICIENT) * target
                }

            lookAheadL[lookAheadIndex] = left.toFloat()
            lookAheadR[lookAheadIndex] = right.toFloat()
            val readIndex = Math.floorMod(lookAheadIndex - LOOKAHEAD_SAMPLES, lookAheadL.size)

            buffer[2 * frame] = finish(gain * lookAheadL[readIndex])
            buffer[2 * frame + 1] = finish(gain * lookAheadR[readIndex])

            historyIndex++
            if (historyIndex >= TAPS) historyIndex = 0
            lookAheadIndex++
            if (lookAheadIndex >= lookAheadL.size) lookAheadIndex = 0
        }
        snapDenormals()
    }

    fun reset() {
        tpHistoryL.fill(0f)
        tpHistoryR.fill(0f)
        lookAheadL.fill(0f)
        lookAheadR.fill(0f)
        historyIndex = 0
        lookAheadIndex = 0
        gain = 1.0
    }

    private fun finish(value: Double): Float {
        val saturated = tanh(value * DspConstants.LIMITER_DRIVE) / TANH_DRIVE
        val bounded = saturated.coerceIn(-HARD_CEILING, HARD_CEILING)
        return bounded.toFloat()
    }

    /** 4x polyphase interpolation of the 12-sample history, phases per 03 §2.4.1. */
    private fun truePeak(
        history: FloatArray,
        writeIndex: Int,
    ): Double {
        var peak = 0.0
        for (phase in 0..3) {
            var accumulator = 0.0
            for (tap in 0 until TAPS) {
                val index = Math.floorMod(writeIndex - tap, TAPS)
                accumulator += POLYPHASE[phase * TAPS + tap] * history[index]
            }
            val magnitude = abs(accumulator)
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    private fun snapDenormals() {
        if (abs(gain - 1.0) < DspConstants.DENORMAL_SNAP) gain = 1.0
    }

    private companion object {
        const val TAPS = 12
        const val LOOKAHEAD_SAMPLES = 96
        const val THRESHOLD = DspConstants.HARD_CEILING
        const val GAIN_FLOOR = 1e-9

        val HARD_CEILING = DspConstants.HARD_CEILING.toDouble()

        /** tau_att = 1 ms, tau_rel = 80 ms (03 §2.4.2), at 48 kHz. */
        val ATTACK_COEFFICIENT = exp(-1.0 / (DspConstants.SAMPLE_RATE * 0.001))
        val RELEASE_COEFFICIENT = exp(-1.0 / (DspConstants.SAMPLE_RATE * 0.080))
        val TANH_DRIVE = tanh(DspConstants.LIMITER_DRIVE)

        /** Frozen 4x12 sinc/Blackman-Harris polyphase table of 03 §2.4.1 (DC gain 1 per phase). */
        val POLYPHASE =
            doubleArrayOf(
                -0.000001,
                0.000173,
                -0.001877,
                0.009869,
                -0.035760,
                0.122550,
                0.971976,
                -0.087638,
                0.026472,
                -0.006817,
                0.001130,
                -0.000077,
                -0.000017,
                0.000840,
                -0.007212,
                0.033709,
                -0.115904,
                0.440968,
                0.766135,
                -0.155723,
                0.046777,
                -0.011066,
                0.001562,
                -0.000068,
                -0.000068,
                0.001562,
                -0.011066,
                0.046777,
                -0.155723,
                0.766135,
                0.440968,
                -0.115904,
                0.033709,
                -0.007212,
                0.000840,
                -0.000017,
                -0.000077,
                0.001130,
                -0.006817,
                0.026472,
                -0.087638,
                0.971976,
                0.122550,
                -0.035760,
                0.009869,
                -0.001877,
                0.000173,
                -0.000001,
            )
    }
}
