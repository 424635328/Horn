package com.industrial.horn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSafetyPolicyTest {
    @Test
    fun headphonesAboveSeventyPercentEnableProtection() {
        assertFalse(AudioSafetyPolicy.evaluate(10, 15, true).earProtectionActive)
        assertTrue(AudioSafetyPolicy.evaluate(11, 15, true).earProtectionActive)
        assertFalse("Protection must never engage on speakers", AudioSafetyPolicy.evaluate(15, 15, false).earProtectionActive)
    }

    @Test
    fun volumeIsClampedAndZeroIsDetectable() {
        val zero = AudioSafetyPolicy.evaluate(-3, 15, false)
        assertEquals(0, zero.mediaVolume)
        assertTrue(zero.isZeroVolume)

        val overMax = AudioSafetyPolicy.evaluate(99, 15, false)
        assertEquals(15, overMax.mediaVolume)
        assertEquals(1f, overMax.volumeFraction, 0f)
    }

    @Test
    fun sixtyPercentTargetRoundsToAUsableStep() {
        assertEquals(9, AudioSafetyPolicy.sixtyPercentVolume(15))
        assertEquals(6, AudioSafetyPolicy.sixtyPercentVolume(10))
        assertEquals(1, AudioSafetyPolicy.sixtyPercentVolume(1))
    }
}
