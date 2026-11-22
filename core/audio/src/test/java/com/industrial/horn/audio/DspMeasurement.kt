package com.industrial.horn.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal object DspMeasurement {
    const val SAMPLE_RATE = 48_000

    /** Renders the raw synthesizer and returns the mono fold `(L+R)/2` of the stereo output. */
    fun renderRaw(
        type: HornType,
        seconds: Double = 3.0,
    ): FloatArray {
        val synth = HornSynthesizerFactory.create(type, SAMPLE_RATE)
        synth.reset()
        val frames = (SAMPLE_RATE * seconds).toInt()
        val stereo = FloatArray(2 * frames)
        synth.render(stereo, frames)
        return monoFold(stereo, frames)
    }

    /** Renders the raw synthesizer as interleaved stereo, no fold. */
    fun renderRawStereo(
        type: HornType,
        seconds: Double = 3.0,
    ): FloatArray {
        val synth = HornSynthesizerFactory.create(type, SAMPLE_RATE)
        synth.reset()
        val frames = (SAMPLE_RATE * seconds).toInt()
        val stereo = FloatArray(2 * frames)
        synth.render(stereo, frames)
        return stereo
    }

    /** Renders through the full [HornRenderer] pipeline and returns the mono fold. */
    fun renderThroughVoice(
        type: HornType,
        seconds: Double = 2.0,
    ): FloatArray {
        val renderer = HornRenderer(SAMPLE_RATE, FRAMES_PER_BLOCK, type)
        val totalFrames = (SAMPLE_RATE * seconds).toInt()
        val blocks = totalFrames / FRAMES_PER_BLOCK
        val stereo = FloatArray(2 * blocks * FRAMES_PER_BLOCK)
        renderer.start()
        for (block in 0 until blocks) {
            val frames = renderer.renderBlock()
            for (index in 0 until 2 * frames) {
                stereo[block * 2 * FRAMES_PER_BLOCK + index] = renderer.outputBuffer[index]
            }
        }
        renderer.panic()
        return monoFold(stereo, blocks * FRAMES_PER_BLOCK)
    }

    /** Mono fold `(L+R)/2` of an interleaved stereo buffer (02 §5). */
    fun monoFold(
        stereo: FloatArray,
        frames: Int,
    ): FloatArray {
        val folded = FloatArray(frames)
        for (index in 0 until frames) {
            folded[index] = (stereo[2 * index] + stereo[2 * index + 1]) * 0.5f
        }
        return folded
    }

    fun rms(
        samples: FloatArray,
        startFraction: Double = 0.2,
    ): Double {
        val start = (samples.size * startFraction).toInt().coerceIn(0, samples.size)
        if (start >= samples.size) return 0.0
        var sum = 0.0
        for (index in start until samples.size) {
            sum += samples[index].toDouble() * samples[index]
        }
        return sqrt(sum / (samples.size - start))
    }

    fun peak(samples: FloatArray): Double = samples.maxOf { abs(it.toDouble()) }

    fun magnitude(
        samples: FloatArray,
        frequencyHz: Double,
        startFraction: Double = 0.2,
    ): Double {
        val start = (samples.size * startFraction).toInt().coerceIn(0, samples.size)
        val angularStep = 2.0 * PI * frequencyHz / SAMPLE_RATE
        var real = 0.0
        var imaginary = 0.0
        for (index in start until samples.size) {
            val angle = angularStep * index
            real += samples[index] * cos(angle)
            imaginary -= samples[index] * sin(angle)
        }
        val count = samples.size - start
        return 2.0 * hypot(real, imaginary) / count
    }

    fun detunedPairMagnitude(
        samples: FloatArray,
        centerHz: Double,
        detuneRatio: Double = 0.004,
    ): Double =
        magnitude(samples, centerHz * (1.0 - detuneRatio)) +
            magnitude(samples, centerHz * (1.0 + detuneRatio))

    fun zeroCrossingRate(
        samples: FloatArray,
        startFraction: Double = 0.2,
    ): Double {
        val start = (samples.size * startFraction).toInt().coerceIn(0, samples.size)
        var crossings = 0
        for (index in start + 1 until samples.size) {
            if ((samples[index - 1] < 0f) != (samples[index] < 0f)) crossings++
        }
        return crossings.toDouble() / (samples.size - start)
    }

    fun spectralCentroid(samples: FloatArray): Double {
        val bands = doubleArrayOf(125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0)
        var weighted = 0.0
        var total = 0.0
        for (band in bands) {
            val energy = magnitude(samples, band)
            weighted += energy * band
            total += energy
        }
        return if (total > 0.0) weighted / total else 0.0
    }

    fun windowedRmsVariation(
        samples: FloatArray,
        windowMs: Double = 20.0,
    ): Double {
        val window = (SAMPLE_RATE * windowMs / 1000.0).toInt().coerceAtLeast(1)
        val start = (samples.size * 0.2).toInt()
        var minRms = Double.MAX_VALUE
        var maxRms = 0.0
        var index = start
        while (index + window <= samples.size) {
            var sum = 0.0
            for (offset in 0 until window) {
                val value = samples[index + offset].toDouble()
                sum += value * value
            }
            val value = sqrt(sum / window)
            minRms = minOf(minRms, value)
            maxRms = maxOf(maxRms, value)
            index += window
        }
        return if (minRms > 1e-9) maxRms / minRms else Double.POSITIVE_INFINITY
    }

    const val FRAMES_PER_BLOCK = 240
}
