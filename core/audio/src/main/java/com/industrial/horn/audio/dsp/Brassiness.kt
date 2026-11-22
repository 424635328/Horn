package com.industrial.horn.audio.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.tanh

/**
 * Scheme (2) of 01 §2.3: band-limited asymmetric wave steepening — the electromagnetic
 * diaphragm pre-stage that replaces the old per-synth `softSaturate` (01 recommends replacing
 * rather than stacking saturators). Bounded by construction: for `|u| <= 1` the output is the
 * normalised tanh shape, so `|y| < 1`.
 *
 * ```
 * w = tanh(k * (u + alpha*(u*u - 1/3)))
 * y = w / tanh(k * (1 + alpha*2/3))
 * ```
 */
internal class AsymmetricSaturator {
    fun shape(
        x: Double,
        k: Double,
        alpha: Double,
    ): Double {
        val u = x.coerceIn(-1.0, 1.0)
        val drive = k.coerceIn(0.25, 6.0)
        val bound = tanh(drive * (1.0 + alpha * 2.0 / 3.0))
        return tanh(drive * (u + alpha * (u * u - 1.0 / 3.0))) / bound
    }
}

/**
 * Scheme (1) of 01 §2.3, the primary brassiness algorithm: amplitude-dependent fractional delay.
 * Pressure peaks travel faster than troughs (Cooper & Abel, DAFx-10), which steepens the wave
 * front; the modulation signal is low-pass smoothed to keep modulation side bands below ~80 Hz.
 *
 * ```
 * s[n]   = onePole(s[n-1], |x[n]|, tau_s)        // tau_s = 2..5 ms
 * d_eff  = d0 * (1 + beta * s[n])                // beta <= 0.35 -> d_eff in [0.65, 1.35] * d0
 * y[n]   = x_lin[n - d_eff]                      // linear-interpolated fractional delay
 * ```
 *
 * Pure feed-forward: `|y| <= max|x|` and `d_eff > 0.25` samples always, so the stage cannot
 * blow up for any parameter combination (01 §3.2).
 */
internal class AmplitudeDelaySteepener(
    sampleRate: Int,
    delayMillis: Double,
    envelopeMillis: Float = 3f,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(delayMillis > 0.0) { "delayMillis must be positive" }
    }

    private val baseDelay = delayMillis * 1e-3 * sampleRate
    private val line = FloatArray(ceil(baseDelay * (1.0 + MAX_BETA)).toInt() + 4)
    private val envelopeSmoother = OnePoleSmoother(sampleRate, envelopeMillis, 0f)
    private var writeIndex = 0

    fun process(
        x: Double,
        beta: Double,
    ): Double {
        val clampedBeta = beta.coerceIn(0.0, MAX_BETA)
        val envelope = envelopeSmoother.next(abs(x).toFloat()).toDouble()

        line[writeIndex] = x.toFloat()

        val delay =
            (baseDelay * (1.0 + clampedBeta * envelope))
                .coerceIn(0.25, line.size - 2.0)
        val readPosition = writeIndex - delay
        val index = Math.floor(readPosition).toInt()
        val fraction = readPosition - index
        val i0 = Math.floorMod(index, line.size)
        val i1 = Math.floorMod(index + 1, line.size)
        val y = line[i0] * (1.0 - fraction) + line[i1] * fraction

        writeIndex++
        if (writeIndex >= line.size) writeIndex = 0
        return y
    }

    fun reset() {
        line.fill(0f)
        writeIndex = 0
        envelopeSmoother.reset()
    }

    private companion object {
        const val MAX_BETA = 0.35
    }
}
