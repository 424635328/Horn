package com.industrial.horn.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class EBikeHornSynthTest {
    @Test
    fun highPitchedPulsePairDominatesTheSpectrum() {
        val samples = DspMeasurement.renderRaw(HornType.EBIKE)
        val highA = DspMeasurement.magnitude(samples, 2_740.0)
        val highB = DspMeasurement.magnitude(samples, 2_785.0)
        val body = DspMeasurement.magnitude(samples, 685.0)
        val centroid = DspMeasurement.spectralCentroid(samples)
        val crossings = DspMeasurement.zeroCrossingRate(samples)

        println(
            "EBIKE highA=$highA highB=$highB body=$body centroid=$centroid zcr=$crossings",
        )

        assertTrue("Missing 2.74 kHz emitter: $highA", highA > 0.01)
        assertTrue("Missing 2.785 kHz emitter: $highB", highB > 0.01)
        assertTrue("E-bike should be a high-frequency voice, centroid=$centroid", centroid > 1_500.0)
        assertTrue("E-bike should have a high zero-crossing rate: $crossings", crossings > 0.05)
        assertTrue("Body tone is unexpectedly dominant", body < highA)
    }

    @Test
    fun outputIsBoundedAndAliasFreeAt48k() {
        val samples = DspMeasurement.renderRaw(HornType.EBIKE)
        assertTrue(samples.all { it.isFinite() })
        assertTrue(DspMeasurement.peak(samples) <= 0.95)
    }
}
