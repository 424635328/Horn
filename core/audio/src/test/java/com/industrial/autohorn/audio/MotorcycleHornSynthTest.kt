package com.industrial.autohorn.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class MotorcycleHornSynthTest {
    @Test
    fun discHornHasSingleFundamentalWithFlutterSidebands() {
        val samples = DspMeasurement.renderRaw(HornType.MOTORCYCLE)
        val carrier = DspMeasurement.magnitude(samples, 444.0)
        val lowerSideband = DspMeasurement.magnitude(samples, 415.0)
        val upperSideband = DspMeasurement.magnitude(samples, 473.0)
        val centroid = DspMeasurement.spectralCentroid(samples)

        println(
            "MOTO carrier=$carrier lower=$lowerSideband upper=$upperSideband centroid=$centroid",
        )

        assertTrue("Missing 444 Hz disc fundamental: $carrier", carrier > 0.05)
        assertTrue("29 Hz lower flutter sideband missing: $lowerSideband", lowerSideband > 0.002)
        assertTrue("29 Hz upper flutter sideband missing: $upperSideband", upperSideband > 0.002)
        assertTrue("Motorcycle should stay mid-focused, centroid=$centroid", centroid in 300.0..2_000.0)
    }

    @Test
    fun flutterProducesMeasurableAmplitudeVariation() {
        val samples = DspMeasurement.renderRaw(HornType.MOTORCYCLE)
        val variation = DspMeasurement.windowedRmsVariation(samples, windowMs = 20.0)

        println("MOTO windowed RMS variation=$variation")
        assertTrue("No diaphragm flutter detected: $variation", variation > 1.03)
    }
}
