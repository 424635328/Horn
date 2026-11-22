package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.StereoOutputStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Output-stage contract (00 §1 C8, 03 §2.4), ported from the old PcmRendererTest with the same
 * assertion strength: digital-silence attack, hard ceiling saturation, gain-before-limiter
 * semantics, whole-block preallocation and zero-frame safety.
 */
class StereoOutputStageTest {
    @Test
    fun attackStartsAtDigitalSilenceAndNeverExceedsHeadroom() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        envelope.noteOn()
        val frames = attackSamples()
        val buffer = FloatArray(2 * frames)
        for (frame in 0 until frames) {
            val sample = envelope.nextSample() * DEFAULT_MASTER_GAIN
            buffer[2 * frame] = sample
            buffer[2 * frame + 1] = sample
        }

        StereoOutputStage().process(buffer, frames)

        assertEquals(0f, buffer[0], 0f)
        assertTrue(buffer.none { abs(it) >= 1f })
        assertTrue("Signal did not ramp up: ${buffer[2 * (frames - 1)]}", buffer[2 * (frames - 1)] > 20_000 / 32_768f)
    }

    @Test
    fun outOfRangeInputHitsTheSoftLimiterCeiling() {
        val frames = 1_024
        val buffer = FloatArray(2 * frames) { 5f }

        StereoOutputStage().process(buffer, frames)

        val peak = buffer.maxOf { abs(it) }
        assertEquals(DspConstants.HARD_CEILING, peak, 1e-6f)
        assertTrue(buffer.none { abs(it) >= 1f })
        assertTrue(buffer.none { it < -DspConstants.HARD_CEILING })
        assertTrue(buffer.none { it > DspConstants.HARD_CEILING })
    }

    @Test
    fun perVoiceGainIsAppliedBeforeTheLimiter() {
        val frames = 256
        val loud = FloatArray(2 * frames) { 0.8f }
        val quiet = FloatArray(2 * frames) { 0.8f * 0.25f }

        StereoOutputStage().process(loud, frames)
        StereoOutputStage().process(quiet, frames)

        assertTrue(loud.maxOf { abs(it) } > quiet.maxOf { abs(it) })
        assertTrue(quiet.none { abs(it) > 12_000 / 32_768f })
    }

    @Test
    fun preallocatedStageSupportsTheWholeBlock() {
        val frames = 240
        val buffer = FloatArray(2 * frames) { 0.5f }

        StereoOutputStage().process(buffer, frames)

        assertTrue(buffer.any { it != 0f })
    }

    @Test
    fun zeroFrameRenderWritesNothing() {
        val buffer = FloatArray(32) { 123f }
        StereoOutputStage().process(buffer, 0)
        assertTrue(buffer.all { it == 123f })
    }

    private fun attackSamples(): Int = (SAMPLE_RATE * AttackReleaseEnvelope.DEFAULT_ATTACK_MS / 1000f).toInt()

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val DEFAULT_MASTER_GAIN = 0.9f
    }
}
