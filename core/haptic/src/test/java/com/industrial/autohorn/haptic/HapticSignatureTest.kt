package com.industrial.autohorn.haptic

import com.industrial.autohorn.audio.HornType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticSignatureTest {
    @Test
    fun everyVoiceHasAValidDistinctPattern() {
        val signatures = HornType.entries.map { HapticSignatures.forType(it) }

        signatures.forEachIndexed { index, pattern ->
            val type = HornType.entries[index]
            assertEquals("$type timing/amplitude mismatch", pattern.timingsMs.size, pattern.amplitudes.size)
            assertTrue("$type has an empty waveform", pattern.timingsMs.isNotEmpty())
            assertTrue("$type has a non-positive timing", pattern.timingsMs.all { it > 0L })
            assertTrue("$type amplitude outside 0..255", pattern.amplitudes.all { it in 0..255 })
            assertTrue("$type has no active pulse", pattern.amplitudes.any { it > 0 })
            assertTrue("$type fallback empty", pattern.fallbackTimingsMs.isNotEmpty())
            assertTrue("$type fallback timing non-positive", pattern.fallbackTimingsMs.all { it > 0L })
        }

        assertEquals(HornType.entries.size, signatures.map { it.timingsMs.toList() }.toSet().size)
    }

    @Test
    fun trainIsHeavierAndEbikeIsLighterThanCar() {
        val car = HapticSignatures.forType(HornType.CAR)
        val train = HapticSignatures.forType(HornType.TRAIN)
        val ebike = HapticSignatures.forType(HornType.EBIKE)

        assertTrue("Train should use maximum amplitude", train.amplitudes.max() > car.amplitudes.max())
        assertTrue("E-bike should be lighter than car", ebike.amplitudes.max() < car.amplitudes.max())
        assertTrue(
            "E-bike pulse train should be faster than train",
            ebike.timingsMs.sum() < train.timingsMs.sum(),
        )
        assertNotEquals(car.timingsMs.toList(), ebike.timingsMs.toList())
    }
}
