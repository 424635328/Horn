package com.industrial.horn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomHornSynthTest {
    @Test
    fun defaultProfileHasTunableFundamentalAndSecondVoice() {
        val samples = DspMeasurement.renderRaw(HornType.CUSTOM)
        val fundamental = DspMeasurement.magnitude(samples, 620.0)
        val second = DspMeasurement.magnitude(samples, 930.0)

        println("CUSTOM fundamental=$fundamental second=$second")
        assertTrue("Missing custom fundamental", fundamental > 0.01)
        assertTrue("Missing custom secondary frequency", second > 0.003)
        assertTrue(samples.all { it.isFinite() })
        assertTrue(DspMeasurement.peak(samples) <= 0.95)
    }

    @Test
    fun extremeProfilesStayFiniteAndBounded() {
        val profiles =
            listOf(
                CustomHornProfile(
                    primaryFrequencyHz = 150f,
                    secondaryFrequencyRatio = 0.5f,
                    dualTone = true,
                    harmonicRichness = 1f,
                    attackMillis = 3f,
                    releaseMillis = 5f,
                    saturation = 1f,
                ),
                CustomHornProfile(
                    primaryFrequencyHz = 3000f,
                    secondaryFrequencyRatio = 2f,
                    dualTone = false,
                    harmonicRichness = 0f,
                    attackMillis = 120f,
                    releaseMillis = 250f,
                    saturation = 0f,
                ),
            )

        for (profile in profiles) {
            val synth =
                HornSynthesizerFactory.create(
                    HornType.CUSTOM,
                    DspMeasurement.SAMPLE_RATE,
                    profile,
                )
            synth.reset()
            val frames = DspMeasurement.SAMPLE_RATE
            val stereo = FloatArray(2 * frames)
            synth.render(stereo, frames)
            val samples = DspMeasurement.monoFold(stereo, frames)
            assertTrue(samples.all { it.isFinite() })
            assertTrue("Peak escaped bounds", DspMeasurement.peak(stereo) <= 0.95)
        }
    }

    @Test
    fun sanitizerClampsEveryUnsafeValue() {
        val invalid =
            CustomHornProfile(
                primaryFrequencyHz = Float.NaN,
                secondaryFrequencyRatio = -1f,
                harmonicRichness = 5f,
                attackMillis = 0f,
                releaseMillis = 999f,
                saturation = -2f,
            )
        val valid = invalid.sanitized()

        assertTrue(valid.primaryFrequencyHz in 150f..3000f)
        assertTrue(valid.primaryFrequencyHz.isFinite())
        assertEquals(0.5f, valid.secondaryFrequencyRatio, 0f)
        assertEquals(1f, valid.harmonicRichness, 0f)
        assertEquals(3f, valid.attackMillis, 0f)
        assertEquals(250f, valid.releaseMillis, 0f)
        assertEquals(0f, valid.saturation, 0f)
    }

    @Test
    fun higherRichnessProducesABrighterSpectrum() {
        val dark = renderProfile(CustomHornProfile(harmonicRichness = 0f, saturation = 0.1f))
        val bright = renderProfile(CustomHornProfile(harmonicRichness = 1f, saturation = 0.1f))

        val darkSecond = DspMeasurement.magnitude(dark, 1_240.0)
        val brightSecond = DspMeasurement.magnitude(bright, 1_240.0)
        assertTrue("Richness did not brighten harmonics", brightSecond > darkSecond)
    }

    private fun renderProfile(profile: CustomHornProfile): FloatArray {
        val synth = CustomHornSynth(DspMeasurement.SAMPLE_RATE, profile)
        synth.reset()
        val frames = DspMeasurement.SAMPLE_RATE
        val stereo = FloatArray(2 * frames)
        synth.render(stereo, frames)
        return DspMeasurement.monoFold(stereo, frames)
    }
}
