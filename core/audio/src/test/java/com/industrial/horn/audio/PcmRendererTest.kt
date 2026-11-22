package com.industrial.horn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class PcmRendererTest {
    @Test
    fun attackStartsAtDigitalSilenceAndNeverExceedsHeadroom() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        envelope.noteOn()
        val output = ShortArray(attackSamples())
        val renderer = PcmRenderer()

        renderer.render(ConstantSynthesizer(1f), envelope, output, output.size)

        assertEquals(0, output.first().toInt())
        assertTrue(output.none { abs(it.toInt()) >= Short.MAX_VALUE.toInt() })
        assertTrue("Signal did not ramp up: ${output.last()}", output.last() > 20_000)
    }

    @Test
    fun outOfRangeSynthesizerOutputHitsTheSoftLimiterCeiling() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        envelope.noteOn()
        repeat(attackSamples()) { envelope.nextSample() }
        val output = ShortArray(1_024)
        val renderer = PcmRenderer(masterGain = 1f)

        renderer.render(ConstantSynthesizer(5f), envelope, output, output.size)

        val peak = output.maxOf { abs(it.toInt()) }
        val expectedCeiling =
            (PcmRenderer.HARD_CEILING.toDouble() * Short.MAX_VALUE).roundToInt()
        assertEquals(expectedCeiling, peak)
        assertTrue(output.none { it == Short.MIN_VALUE })
        assertTrue(output.none { it == Short.MAX_VALUE })
    }

    @Test
    fun perVoiceGainIsAppliedBeforeTheLimiter() {
        val loudEnvelope = AttackReleaseEnvelope(SAMPLE_RATE)
        loudEnvelope.noteOn()
        repeat(attackSamples()) { loudEnvelope.nextSample() }
        val quietEnvelope = AttackReleaseEnvelope(SAMPLE_RATE)
        quietEnvelope.noteOn()
        repeat(attackSamples()) { quietEnvelope.nextSample() }
        val loud = ShortArray(256)
        val quiet = ShortArray(256)

        PcmRenderer(masterGain = 1f).render(
            ConstantSynthesizer(0.8f),
            loudEnvelope,
            loud,
            loud.size,
            voiceGain = 1f,
        )
        PcmRenderer(masterGain = 1f).render(
            ConstantSynthesizer(0.8f),
            quietEnvelope,
            quiet,
            quiet.size,
            voiceGain = 0.25f,
        )

        assertTrue(loud.maxOf { abs(it.toInt()) } > quiet.maxOf { abs(it.toInt()) })
        assertTrue(quiet.none { abs(it.toInt()) > 12_000 })
    }

    @Test
    fun preallocatedRendererSupportsTheWholeBlock() {
        val envelope = AttackReleaseEnvelope(SAMPLE_RATE)
        envelope.noteOn()
        val output = ShortArray(240)
        val renderer = PcmRenderer(maxFrames = 240)

        renderer.render(ConstantSynthesizer(0.5f), envelope, output, output.size)

        assertTrue(output.any { it.toInt() != 0 })
    }

    @Test
    fun zeroFrameRenderWritesNothing() {
        val output = ShortArray(16) { 123 }
        PcmRenderer().render(ConstantSynthesizer(1f), AttackReleaseEnvelope(SAMPLE_RATE), output, 0)
        assertTrue(output.all { it.toInt() == 123 })
    }

    private fun attackSamples(): Int = (SAMPLE_RATE * AttackReleaseEnvelope.DEFAULT_ATTACK_MS / 1000f).toInt()

    private class ConstantSynthesizer(
        private val value: Float,
        override val sampleRate: Int = SAMPLE_RATE,
    ) : HornSynthesizer {
        override fun render(
            buffer: FloatArray,
            frameCount: Int,
        ) {
            for (index in 0 until frameCount) buffer[index] = value
        }

        override fun reset() = Unit
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
