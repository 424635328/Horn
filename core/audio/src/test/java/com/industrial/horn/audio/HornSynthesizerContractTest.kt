package com.industrial.horn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HornSynthesizerContractTest {
    @Test
    fun everyVoiceIsFiniteBoundedAndAudible() {
        for (type in HornType.entries) {
            val samples = DspMeasurement.renderRaw(type)
            assertTrue("$type contains NaN", samples.all { it.isFinite() })
            val peak = DspMeasurement.peak(samples)
            val rms = DspMeasurement.rms(samples)
            assertTrue("$type peak=$peak exceeds output ceiling", peak <= 0.95)
            assertTrue("$type is effectively silent: rms=$rms", rms > 0.02)
            assertTrue("$type peak is implausibly low: $peak", peak > 0.20)
        }
    }

    @Test
    fun factoryReturnsAPhysicallyDifferentImplementationPerType() {
        val engines =
            HornType.entries.map { type ->
                type to HornSynthesizerFactory.create(type, DspMeasurement.SAMPLE_RATE)
            }
        assertEquals(HornType.entries.size, engines.map { it.second::class }.toSet().size)
        engines.forEach { (type, synth) ->
            assertNotNull("Factory returned null for $type", synth)
            assertEquals(DspMeasurement.SAMPLE_RATE, synth.sampleRate)
        }
    }

    @Test
    fun voiceProfilesMatchTheSpecifiedTransientWindows() {
        val car = HornVoiceCatalog.profile(HornType.CAR)
        assertTrue(car.attackMillis in 10f..15f)
        assertTrue(car.releaseMillis in 10f..15f)

        val train = HornVoiceCatalog.profile(HornType.TRAIN)
        assertTrue(train.attackMillis in 50f..80f)
        assertTrue(train.releaseMillis in 150f..220f)
        assertTrue("Train air noise would be inaudible", train.makeupGain > 0f)

        val ebike = HornVoiceCatalog.profile(HornType.EBIKE)
        assertTrue(ebike.attackMillis in 3f..5f)
        assertTrue(ebike.releaseMillis in 5f..8f)

        val motorcycle = HornVoiceCatalog.profile(HornType.MOTORCYCLE)
        assertEquals(8f, motorcycle.attackMillis, 0f)
        assertEquals(10f, motorcycle.releaseMillis, 0f)

        val custom = HornVoiceCatalog.profile(HornType.CUSTOM)
        assertTrue(custom.attackMillis in 3f..120f)
        assertTrue(custom.releaseMillis in 5f..250f)

        HornType.entries.forEach { type ->
            val gain = HornVoiceCatalog.profile(type).makeupGain
            assertTrue("$type makeup gain out of range", gain in 0.1f..1f)
        }
    }

    @Test
    fun everyVoiceEnvelopeIsSmoothAndReachesExactSilence() {
        HornType.entries.forEach { type ->
            val profile = HornVoiceCatalog.profile(type)
            val envelope =
                AttackReleaseEnvelope(
                    sampleRate = DspMeasurement.SAMPLE_RATE,
                    attackMillis = profile.attackMillis,
                    releaseMillis = profile.releaseMillis,
                )
            val attackSamples =
                (DspMeasurement.SAMPLE_RATE * profile.attackMillis / 1000f).toInt()
            val releaseSamples =
                (DspMeasurement.SAMPLE_RATE * profile.releaseMillis / 1000f).toInt()

            envelope.noteOn()
            var previous = 0f
            repeat(attackSamples) {
                val current = envelope.nextSample()
                assertTrue("$type attack decreased", current >= previous)
                assertTrue("$type attack jumped by ${current - previous}", current - previous < 0.02f)
                previous = current
            }
            assertEquals("$type did not reach full level", 1f, envelope.nextSample(), 0.0001f)

            envelope.noteOff()
            previous = 1f
            repeat(releaseSamples) {
                val current = envelope.nextSample()
                assertTrue("$type release increased", current <= previous)
                assertTrue("$type release jumped by ${previous - current}", previous - current < 0.02f)
                previous = current
            }
            assertEquals("$type did not reach silence", 0f, envelope.nextSample(), 0f)
        }
    }

    @Test
    fun eachVoiceFactoryCreatesTheExpectedEngine() {
        assertTrue(
            HornSynthesizerFactory.create(HornType.CAR, DspMeasurement.SAMPLE_RATE)
                is DualToneHornSynth,
        )
        assertTrue(
            HornSynthesizerFactory.create(HornType.TRAIN, DspMeasurement.SAMPLE_RATE)
                is TrainHornSynth,
        )
        assertTrue(
            HornSynthesizerFactory.create(HornType.EBIKE, DspMeasurement.SAMPLE_RATE)
                is EBikeHornSynth,
        )
        assertTrue(
            HornSynthesizerFactory.create(HornType.MOTORCYCLE, DspMeasurement.SAMPLE_RATE)
                is MotorcycleHornSynth,
        )
        assertTrue(
            HornSynthesizerFactory.create(HornType.CUSTOM, DspMeasurement.SAMPLE_RATE)
                is CustomHornSynth,
        )
    }
}
