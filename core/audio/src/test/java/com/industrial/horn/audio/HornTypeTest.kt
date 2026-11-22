package com.industrial.horn.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class HornTypeTest {
    @Test
    fun everyTypeRoundTripsThroughItsStorageKey() {
        HornType.entries.forEach { type ->
            assertEquals(type, HornType.fromStorageKey(type.storageKey))
        }
    }

    @Test
    fun missingOrUnknownStorageKeyFallsBackToCar() {
        assertEquals(HornType.CAR, HornType.fromStorageKey(null))
        assertEquals(HornType.CAR, HornType.fromStorageKey(""))
        assertEquals(HornType.CAR, HornType.fromStorageKey("spaceship"))
    }
}
