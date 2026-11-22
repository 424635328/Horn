package com.industrial.horn.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OnePoleSmootherTest {
    @Test
    fun convergesToTargetWithoutOvershoot() {
        val smoother = OnePoleSmoother(sampleRate = 48_000, timeMillis = 10f, initialValue = 0f)
        var value = 0f
        var sample = 0
        while (sample < 48_000 && abs(value - 1f) > 1e-4f) {
            value = smoother.next(1f)
            assertTrue("Must never overshoot the target", value <= 1f + 1e-6f)
            sample++
        }
        assertTrue("Should converge well within one second", sample < 48_000)
        assertEquals(1f, value, 1e-4f)
    }

    @Test
    fun timeConstantIsHonouredWithinTolerance() {
        // After one time constant the step response reaches ~1 - 1/e (63.2%).
        val smoother = OnePoleSmoother(sampleRate = 48_000, timeMillis = 10f, initialValue = 0f)
        val samplesForTau = 480
        var value = 0f
        repeat(samplesForTau) { value = smoother.next(1f) }
        assertEquals(0.632f, value, 0.03f)
    }

    @Test
    fun stepwiseTargetProducesMonotonicSequence() {
        val smoother = OnePoleSmoother(sampleRate = 48_000, timeMillis = 20f, initialValue = 0f)
        var previous = 0f
        repeat(2_000) {
            val value = smoother.next(0.5f)
            assertTrue("Smoothed sequence must be monotonic for a step", value >= previous - 1e-7f)
            previous = value
        }
    }

    @Test
    fun setCurrentSnapsImmediately() {
        val smoother = OnePoleSmoother(sampleRate = 48_000, timeMillis = 5f, initialValue = 0f)
        smoother.setCurrent(-1f)
        assertEquals(-1f, smoother.current, 0f)
        val first = smoother.next(1f)
        assertTrue("Must start moving up from the snapped value", first > -1f && first < 1f)
    }
}
