package com.industrial.autohorn.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SpatialReverbTest {
    @Test
    fun tunnelKeepsMoreLateEnergyThanStreet() {
        val street = impulseResponse(AcousticSpace.STREET)
        val tunnel = impulseResponse(AcousticSpace.TUNNEL)
        val openRoad = impulseResponse(AcousticSpace.OPEN_ROAD)

        val streetLate = rms(street, fromMillis = 250, toMillis = 500)
        val tunnelLate = rms(tunnel, fromMillis = 250, toMillis = 500)
        val openLate = rms(openRoad, fromMillis = 150, toMillis = 400)

        println("REVERB streetLate=$streetLate tunnelLate=$tunnelLate openLate=$openLate")
        assertTrue("Tunnel should retain more late energy", tunnelLate > streetLate * 2.0)
        assertTrue("Open-road tail should be audible", openLate > 0.0001)
        assertTrue("Tunnel tail should be audible", tunnelLate > 0.0002)
    }

    @Test
    fun allSpacesRemainFiniteAndBoundedAfterFinalLimit() {
        for (space in AcousticSpace.entries) {
            val samples = impulseResponse(space)
            assertTrue("$space contains NaN", samples.all { it.isFinite() })
            assertTrue("$space is completely silent", samples.any { it != 0f })
        }
    }

    private fun impulseResponse(space: AcousticSpace): FloatArray {
        val reverb = SpatialReverb(SAMPLE_RATE)
        reverb.setSpace(space)
        val samples = FloatArray(SAMPLE_RATE / 2)
        samples[0] = 1f
        reverb.process(samples, samples.size)
        var peak = 0f
        for (sample in samples) peak = maxOf(peak, kotlin.math.abs(sample))
        // The renderer applies the final limiter; the raw reverb is allowed to overshoot but must
        // stay in a sane range so float conversion never produces infinity.
        assertTrue("$space raw reverb runaway: $peak", peak < 20f)
        return samples
    }

    private fun rms(
        samples: FloatArray,
        fromMillis: Int,
        toMillis: Int,
    ): Double {
        val from = (SAMPLE_RATE * fromMillis / 1_000).coerceIn(0, samples.size)
        val to = (SAMPLE_RATE * toMillis / 1_000).coerceIn(from, samples.size)
        var sum = 0.0
        for (index in from until to) {
            sum += samples[index].toDouble() * samples[index]
        }
        return if (to > from) sqrt(sum / (to - from)) else 0.0
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
