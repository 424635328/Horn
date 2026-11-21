package com.industrial.autohorn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AttackReleaseEnvelopeTest {
    @Test
    fun attackIsSmoothMonotonicAndCompletesInTwelveMilliseconds() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        val attackSamples = (SAMPLE_RATE * AttackReleaseEnvelope.DEFAULT_ATTACK_MS / 1000f).toInt()
        val samples = FloatArray(attackSamples)
        envelope.noteOn()

        for (index in samples.indices) {
            samples[index] = envelope.nextSample()
        }

        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0.0001f)
        for (index in 1 until samples.size) {
            val delta = samples[index] - samples[index - 1]
            assertTrue("Attack decreased at sample $index", delta >= 0f)
            assertTrue("Attack step too large at sample $index: $delta", delta < 0.02f)
        }
        assertFalse(envelope.isSilent)
    }

    @Test
    fun releaseIsSmoothAndReachesExactSilenceInFourteenMilliseconds() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        envelope.noteOn()
        repeat(attackSamples()) { envelope.nextSample() }
        envelope.noteOff()

        val releaseSamples = (SAMPLE_RATE * AttackReleaseEnvelope.DEFAULT_RELEASE_MS / 1000f).toInt()
        val samples = FloatArray(releaseSamples + 1)
        for (index in samples.indices) {
            samples[index] = envelope.nextSample()
        }

        assertEquals(1f, samples.first(), 0.0001f)
        assertEquals(0f, samples.last(), 0f)
        for (index in 1 until samples.size) {
            val delta = samples[index - 1] - samples[index]
            assertTrue("Release increased at sample $index", delta >= 0f)
            assertTrue("Release step too large at sample $index: $delta", delta < 0.02f)
        }
        assertTrue(envelope.isSilent)
    }

    @Test
    fun retriggerDuringReleaseStartsFromCurrentLevelWithoutAJump() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        envelope.noteOn()
        repeat(attackSamples()) { envelope.nextSample() }
        envelope.noteOff()
        repeat(attackSamples() / 3) { envelope.nextSample() }

        val levelBeforeRetrigger = envelope.nextSample()
        envelope.noteOn()
        val firstAttackSample = envelope.nextSample()

        assertTrue(levelBeforeRetrigger in 0.05f..0.95f)
        assertTrue(
            "Retrigger jumped from $levelBeforeRetrigger to $firstAttackSample",
            abs(firstAttackSample - levelBeforeRetrigger) < 0.001f,
        )
    }

    @Test
    fun noteOffWhileIdleIsANoOp() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        envelope.noteOff()
        assertEquals(0f, envelope.nextSample(), 0f)
        assertTrue(envelope.isSilent)
    }

    @Test
    fun envelopeDurationsAreStrictlyInsideTheAntiPopWindow() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        assertTrue(envelope.attackMillis in 10f..15f)
        assertTrue(envelope.releaseMillis in 10f..15f)
    }

    @Test
    fun durationsOutsideTheSupportedVoiceRangeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AttackReleaseEnvelope(SAMPLE_RATE, attackMillis = 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttackReleaseEnvelope(SAMPLE_RATE, releaseMillis = 251f)
        }
    }

    @Test
    fun trainAndEbikeTransientDurationsAreAccepted() {
        val train = AttackReleaseEnvelope(SAMPLE_RATE, attackMillis = 65f, releaseMillis = 185f)
        val ebike = AttackReleaseEnvelope(SAMPLE_RATE, attackMillis = 4f, releaseMillis = 6f)

        train.noteOn()
        repeat((SAMPLE_RATE * 0.2f).toInt()) { train.nextSample() }
        train.noteOff()
        assertTrue(train.isActive)

        ebike.noteOn()
        repeat(16) { ebike.nextSample() }
        assertTrue(ebike.isActive)
    }

    private fun attackSamples(): Int = (SAMPLE_RATE * AttackReleaseEnvelope.DEFAULT_ATTACK_MS / 1000f).toInt()

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
