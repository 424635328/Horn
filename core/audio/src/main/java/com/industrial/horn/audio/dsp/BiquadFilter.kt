package com.industrial.horn.audio.dsp

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct-form-II transposed biquad. Used for the train air band, the e-bike high-pass/band
 * shaping and the motorcycle mid-frequency focus. Coefficients are computed off the audio path.
 */
internal class BiquadFilter private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(input: Double): Double {
        val output = b0 * input + z1
        z1 = b1 * input - a1 * output + z2
        z2 = b2 * input - a2 * output
        return output
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        fun lowPass(
            sampleRate: Int,
            cutoffHz: Double,
            q: Double = 0.707,
        ): BiquadFilter {
            val coefficients = coefficients(sampleRate, cutoffHz, q)
            val cosW = coefficients.cosine
            val alpha = coefficients.alpha
            return BiquadFilter(
                b0 = (1.0 - cosW) / 2.0,
                b1 = 1.0 - cosW,
                b2 = (1.0 - cosW) / 2.0,
                a1 = -2.0 * cosW,
                a2 = 1.0 - alpha,
            ).normalizedBy(1.0 + alpha)
        }

        fun highPass(
            sampleRate: Int,
            cutoffHz: Double,
            q: Double = 0.707,
        ): BiquadFilter {
            val coefficients = coefficients(sampleRate, cutoffHz, q)
            val cosW = coefficients.cosine
            val alpha = coefficients.alpha
            return BiquadFilter(
                b0 = (1.0 + cosW) / 2.0,
                b1 = -(1.0 + cosW),
                b2 = (1.0 + cosW) / 2.0,
                a1 = -2.0 * cosW,
                a2 = 1.0 - alpha,
            ).normalizedBy(1.0 + alpha)
        }

        fun bandPass(
            sampleRate: Int,
            centerHz: Double,
            q: Double,
        ): BiquadFilter {
            val coefficients = coefficients(sampleRate, centerHz, q)
            val alpha = coefficients.alpha
            val cosW = coefficients.cosine
            return BiquadFilter(
                b0 = alpha,
                b1 = 0.0,
                b2 = -alpha,
                a1 = -2.0 * cosW,
                a2 = 1.0 - alpha,
            ).normalizedBy(1.0 + alpha)
        }

        /** RBJ high-frequency shelf, used for the headphone ILD tilt (03 §2.2 coefficient form). */
        fun highShelf(
            sampleRate: Int,
            cutoffHz: Double,
            q: Double,
            gainDb: Double,
        ): BiquadFilter {
            val coefficients = coefficients(sampleRate, cutoffHz, q)
            val cosW = coefficients.cosine
            val alpha = coefficients.alpha
            val a = 10.0.pow(gainDb / 40.0)
            val rootA = sqrt(a)
            val a0 = (a + 1.0) - (a - 1.0) * cosW + 2.0 * rootA * alpha
            return BiquadFilter(
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW + 2.0 * rootA * alpha),
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW),
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW - 2.0 * rootA * alpha),
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW),
                a2 = (a + 1.0) - (a - 1.0) * cosW - 2.0 * rootA * alpha,
            ).normalizedBy(a0)
        }

        /** RBJ peaking EQ, used for the throat formant group (01 §2.2.2 coefficient form). */
        fun peak(
            sampleRate: Int,
            centerHz: Double,
            q: Double,
            gainDb: Double,
        ): BiquadFilter {
            val coefficients = coefficients(sampleRate, centerHz, q)
            val cosW = coefficients.cosine
            val alpha = coefficients.alpha
            val a = 10.0.pow(gainDb / 40.0)
            return BiquadFilter(
                b0 = 1.0 + alpha * a,
                b1 = -2.0 * cosW,
                b2 = 1.0 - alpha * a,
                a1 = -2.0 * cosW,
                a2 = 1.0 - alpha,
            ).normalizedBy(1.0 + alpha / a)
        }

        private fun coefficients(
            sampleRate: Int,
            frequencyHz: Double,
            q: Double,
        ): Coefficients {
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(frequencyHz > 0.0 && frequencyHz < sampleRate / 2.0) {
                "frequency must be inside Nyquist"
            }
            require(q > 0.0) { "q must be positive" }
            val omega = TWO_PI * frequencyHz / sampleRate
            val alpha = sin(omega) / (2.0 * q)
            return Coefficients(cosine = cos(omega), alpha = alpha)
        }
    }

    private fun normalizedBy(a0: Double): BiquadFilter = BiquadFilter(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private data class Coefficients(
        val cosine: Double,
        val alpha: Double,
    )
}
