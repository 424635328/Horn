package com.industrial.horn.audio.dsp

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ISO 9613-1:1993 atmospheric absorption, evaluated from the standard's calculation formulas
 * (identical to Bass et al. 1995, JASA 97(1) 680-683). No coefficient is hand-copied into the
 * runtime: the scene engine derives its shelf gains at construction time from [absorptionDbPerKm],
 * so the "no hand-copied approximations" rule of docs/spec/research/02 §3.3 holds.
 *
 * Reference values reproduced by [absorptionDbPerKm] (20 degC, 50 % RH, 101.325 kPa, the standard's
 * worked conditions): alpha(100 Hz) = 0.294, alpha(1 kHz) = 4.66, alpha(10 kHz) = 158.8 dB/km and
 * relaxation frequencies f_rO = 35.4 kHz, f_rN = 332 Hz. These are asserted in the unit tests.
 */
internal object Iso9613Absorption {
    private const val T0 = 293.15
    private const val T01 = 273.16
    private const val P_REF = 101.325

    /** Pure-tone attenuation coefficient alpha(f) in dB/km at 101.325 kPa. */
    fun absorptionDbPerKm(
        frequencyHz: Double,
        temperatureC: Double = 20.0,
        relativeHumidityPercent: Double = 50.0,
        pressureKpa: Double = P_REF,
    ): Double {
        val tKelvin = temperatureC + 273.15
        require(tKelvin > 0.0) { "temperature must be above absolute zero" }
        require(relativeHumidityPercent in 0.0..100.0) { "humidity must be a percentage" }
        require(pressureKpa > 0.0) { "pressure must be positive" }
        require(frequencyHz >= 0.0) { "frequency must be non-negative" }

        val pRatio = pressureKpa / P_REF
        val tRatio = tKelvin / T0
        val h = molarWaterVapour(relativeHumidityPercent, tKelvin, pRatio)
        val fRO = relaxationOxygen(pRatio, h)
        val fRN = relaxationNitrogen(pRatio, tRatio, h)

        val classical = 1.84e-11 * (1.0 / pRatio) * sqrt(tRatio)
        val oxygen = 0.01275 * exp(-2239.1 / tKelvin) * fRO / (fRO * fRO + frequencyHz * frequencyHz)
        val nitrogen = 0.1068 * exp(-3352.0 / tKelvin) * fRN / (fRN * fRN + frequencyHz * frequencyHz)

        // alpha in dB/m; the caller works in dB/km.
        return 8.686 * frequencyHz * frequencyHz *
            (classical + tRatio.pow(-2.5) * (oxygen + nitrogen)) * 1_000.0
    }

    /** Oxygen relaxation frequency [Hz], 35.4 kHz at 20 degC / 50 % RH / 1 atm. */
    fun relaxationOxygen(
        pRatio: Double,
        h: Double,
    ): Double = pRatio * (24.0 + 4.04e4 * h * (0.02 + h) / (0.391 + h))

    /** Nitrogen relaxation frequency [Hz], 332 Hz at 20 degC / 50 % RH / 1 atm. */
    fun relaxationNitrogen(
        pRatio: Double,
        tRatio: Double,
        h: Double,
    ): Double =
        pRatio * tRatio.pow(-0.5) *
            (9.0 + 280.0 * h * exp(-4.170 * (tRatio.pow(-1.0 / 3.0) - 1.0)))

    private fun molarWaterVapour(
        relativeHumidityPercent: Double,
        tKelvin: Double,
        pRatio: Double,
    ): Double {
        val log10Psat = -6.8346 * (T01 / tKelvin).pow(1.261) + 4.6151
        val psatOverPRef = 10.0.pow(log10Psat)
        return relativeHumidityPercent * psatOverPRef / pRatio
    }
}
