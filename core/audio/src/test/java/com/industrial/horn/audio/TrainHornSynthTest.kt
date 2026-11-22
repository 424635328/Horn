package com.industrial.horn.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainHornSynthTest {
    @Test
    fun chordContainsAllThreeChimesAndLowOctaveBody() {
        val samples = DspMeasurement.renderRaw(HornType.TRAIN)
        val dSharp = DspMeasurement.detunedPairMagnitude(samples, 311.13)
        val fSharp = DspMeasurement.detunedPairMagnitude(samples, 369.99)
        val b = DspMeasurement.detunedPairMagnitude(samples, 493.88)
        val lowDSharp = DspMeasurement.magnitude(samples, 155.56)
        val lowB = DspMeasurement.magnitude(samples, 123.47)
        val centroid = DspMeasurement.spectralCentroid(samples)

        println(
            "TRAIN d#=$dSharp f#=$fSharp b=$b lowD#=$lowDSharp lowB=$lowB centroid=$centroid",
        )

        assertTrue("Missing D#4 chime: $dSharp", dSharp > 0.025)
        assertTrue("Missing F#4 chime: $fSharp", fSharp > 0.02)
        assertTrue("Missing B4 chime: $b", b > 0.012)
        assertTrue("Missing low D# octave: $lowDSharp", lowDSharp > 0.015)
        assertTrue("Missing low B body: $lowB", lowB > 0.008)
        assertTrue("Train should be a low/mid-heavy chord, centroid=$centroid", centroid < 2_000.0)
    }

    @Test
    fun detunedChordIsDeterministicAndBounded() {
        val first = DspMeasurement.renderRaw(HornType.TRAIN, seconds = 1.5)
        val synth = TrainHornSynth(DspMeasurement.SAMPLE_RATE)
        synth.reset()
        val secondStereo = FloatArray(2 * first.size)
        synth.render(secondStereo, first.size)

        assertTrue(secondStereo.all { it.isFinite() })
        assertTrue(DspMeasurement.peak(secondStereo) <= 0.95)
        assertArrayEquals(first, DspMeasurement.monoFold(secondStereo, first.size), 0f)
    }
}
