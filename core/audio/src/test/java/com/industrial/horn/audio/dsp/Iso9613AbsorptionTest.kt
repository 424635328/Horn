package com.industrial.horn.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISO 9613-1 conformance guard: the reference values below are the standard's worked conditions
 * (20 degC, 50 % RH, 101.325 kPa) reproduced by the formula implementation — alpha(100 Hz) =
 * 0.294, alpha(1 kHz) = 4.66, alpha(10 kHz) = 158.8 dB/km, f_rO = 35.4 kHz, f_rN = 332 Hz.
 * Positive control: the cold-air row of the standard's Table 1 (-20 degC, 50 %, 1 kHz = 9.14
 * dB/km) must also be reproduced, so a broken formula cannot pass as "zero measured".
 */
class Iso9613AbsorptionTest {
    @Test
    fun referenceValuesAtTwentyCelsiusFiftyPercentHumidity() {
        assertEquals(0.294, Iso9613Absorption.absorptionDbPerKm(100.0), 0.005)
        assertEquals(4.66, Iso9613Absorption.absorptionDbPerKm(1_000.0), 0.05)
        assertEquals(158.8, Iso9613Absorption.absorptionDbPerKm(10_000.0), 1.5)
    }

    @Test
    fun relaxationFrequenciesMatchTheStandard() {
        val pRatio = 1.0
        val tRatio = 293.15 / 293.15
        val h = 50.0 * vaporPressureRatio(293.15)
        val fRO = Iso9613Absorption.relaxationOxygen(pRatio, h)
        val fRN = Iso9613Absorption.relaxationNitrogen(pRatio, tRatio, h)
        assertEquals(35_400.0, fRO, 500.0)
        assertEquals(332.0, fRN, 5.0)
    }

    @Test
    fun coldAirTableRowIsReproduced() {
        // Standard Table 1 (a): -20 degC, 50 % RH, 1 kHz = 9.14 dB/km (positive control).
        assertEquals(9.14, Iso9613Absorption.absorptionDbPerKm(1_000.0, temperatureC = -20.0), 0.15)
    }

    @Test
    fun absorptionGrowsMonotonicallyAcrossTheAudioBand() {
        var previous = 0.0
        for (frequency in doubleArrayOf(500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0)) {
            val alpha = Iso9613Absorption.absorptionDbPerKm(frequency)
            assertTrue("alpha must grow with frequency at $frequency", alpha > previous)
            previous = alpha
        }
    }

    private fun vaporPressureRatio(tKelvin: Double): Double {
        val log10Psat = -6.8346 * Math.pow(273.16 / tKelvin, 1.261) + 4.6151
        return Math.pow(10.0, log10Psat)
    }
}
