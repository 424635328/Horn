package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.SceneSpecCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Scene-engine contract (docs/spec/research/02), ported from the old SpatialReverbTest with the
 * same assertion strength plus the mono-fold safety budget of 02 §5.
 */
class SceneEngineTest {
    @Test
    fun tunnelKeepsMoreLateEnergyThanStreet() {
        val street = impulseResponse(AcousticSpace.STREET)
        val tunnel = impulseResponse(AcousticSpace.TUNNEL)
        val openRoad = impulseResponse(AcousticSpace.OPEN_ROAD)

        val streetLate = rms(street, fromMillis = 250, toMillis = 500)
        val tunnelLate = rms(tunnel, fromMillis = 250, toMillis = 500)
        val openLate = rms(openRoad, fromMillis = 150, toMillis = 400)

        println("REVERB streetLate=$streetLate tunnelLate=$tunnelLate openLate=$openLate")
        assertTrue("Tunnel should retain more late energy", tunnelLate > streetLate * 2.0)
        assertTrue("Open-road tail should be audible", openLate > 0.0001)
        assertTrue("Tunnel tail should be audible", tunnelLate > 0.0002)
    }

    @Test
    fun allSpacesRemainFiniteAndBoundedAfterFinalLimit() {
        for (space in AcousticSpace.entries) {
            val samples = impulseResponse(space)
            assertTrue("$space contains NaN", samples.all { it.isFinite() })
            assertTrue("$space is completely silent", samples.any { it != 0f })
        }
    }

    @Test
    fun monoFoldKeepsTheEnergyBudgetOfTheSpatialStage() {
        for (space in AcousticSpace.entries) {
            val stereo = stereoImpulseResponse(space)
            val frames = stereo.size / 2
            val folded = DspMeasurement.monoFold(stereo, frames)

            var stereoEnergy = 0.0
            var foldEnergy = 0.0
            for (index in 0 until frames) {
                val l = stereo[2 * index].toDouble()
                val r = stereo[2 * index + 1].toDouble()
                stereoEnergy += 0.5 * (l * l + r * r)
                foldEnergy += folded[index].toDouble() * folded[index]
            }
            val lossDb = 10.0 * kotlin.math.log10(stereoEnergy / foldEnergy.coerceAtLeast(1e-30))
            println("FOLD $space lossDb=$lossDb")
            assertTrue("$space mono fold loses ${lossDb}dB (budget 3dB)", lossDb < 3.0)
        }
    }

    @Test
    fun sceneWetTailIsDecorrelatedButFoldSafe() {
        val space = AcousticSpace.TUNNEL
        val stereo = stereoImpulseResponse(space)
        val frames = stereo.size / 2
        val left = FloatArray(frames) { stereo[2 * it] }
        val right = FloatArray(frames) { stereo[2 * it + 1] }
        val correlation = AudioTelemetryBuffer.stereoCorrelation(left, right, frames)

        println("SCENE correlation=$correlation")
        assertTrue("Correlation must stay bounded", correlation in -1f..1f)
        assertTrue("Wet tail must not be an L=R copy", correlation < 0.999f)
    }

    @Test
    fun combDelaysMatchTheFrozenRoundHalfUpLiterals() {
        // 02 §6.5: round-half-up (NOT toInt() truncation), mirrored 1:1 by dsp_constants.h.
        assertEquals(210, SceneSpecCatalog.spec(AcousticSpace.STREET).combDelaySamples)
        assertEquals(2237, SceneSpecCatalog.spec(AcousticSpace.TUNNEL).combDelaySamples)
        assertEquals(210, SceneSpecCatalog.spec(AcousticSpace.OPEN_ROAD).combDelaySamples)
        assertEquals(672, SceneSpecCatalog.spec(AcousticSpace.GARAGE).combDelaySamples)
    }

    private fun stereoImpulseResponse(space: AcousticSpace): FloatArray {
        val scene = SceneEngine(SAMPLE_RATE)
        scene.hardSetSpace(space)
        val frames = SAMPLE_RATE / 2
        val stereo = FloatArray(2 * frames)
        stereo[0] = 1f
        stereo[1] = 1f
        scene.process(stereo, frames)
        var peak = 0f
        for (sample in stereo) peak = maxOf(peak, abs(sample))
        // The renderer applies the final limiter; the raw scene is allowed to overshoot but must
        // stay in a sane range so float conversion never produces infinity.
        assertTrue("$space raw scene runaway: $peak", peak < 20f)
        return stereo
    }

    private fun impulseResponse(space: AcousticSpace): FloatArray {
        val stereo = stereoImpulseResponse(space)
        return DspMeasurement.monoFold(stereo, stereo.size / 2)
    }

    private fun rms(
        samples: FloatArray,
        fromMillis: Int,
        toMillis: Int,
    ): Double {
        val from = (SAMPLE_RATE * fromMillis / 1_000).coerceIn(0, samples.size)
        val to = (SAMPLE_RATE * toMillis / 1_000).coerceIn(from, samples.size)
        var sum = 0.0
        for (index in from until to) {
            sum += samples[index].toDouble() * samples[index]
        }
        return if (to > from) sqrt(sum / (to - from)) else 0.0
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
