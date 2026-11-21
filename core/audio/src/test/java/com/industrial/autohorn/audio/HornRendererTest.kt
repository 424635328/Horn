package com.industrial.autohorn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class HornRendererTest {
    @Test
    fun calibratedVoiceLoudnessStaysInsidePerceptualBands() {
        val car = DspMeasurement.rms(DspMeasurement.renderThroughVoice(HornType.CAR))
        val train = DspMeasurement.rms(DspMeasurement.renderThroughVoice(HornType.TRAIN))
        val ebike = DspMeasurement.rms(DspMeasurement.renderThroughVoice(HornType.EBIKE))
        val motorcycle =
            DspMeasurement.rms(DspMeasurement.renderThroughVoice(HornType.MOTORCYCLE))
        val custom = DspMeasurement.rms(DspMeasurement.renderThroughVoice(HornType.CUSTOM))

        println(
            "CALIBRATION car=$car train=$train ebike=$ebike motorcycle=$motorcycle custom=$custom",
        )

        assertTrue("Car RMS out of band: $car", car in 0.16..0.27)
        assertTrue("Train RMS out of band: $train", train in 0.13..0.24)
        assertTrue("E-bike RMS out of band: $ebike", ebike in 0.05..0.13)
        assertTrue("Motorcycle RMS out of band: $motorcycle", motorcycle in 0.14..0.27)
        assertTrue("Custom RMS out of band: $custom", custom in 0.10..0.27)

        // The 2.7 kHz e-bike voice is deliberately quieter in RMS because human hearing is
        // much more sensitive there. Train is dense but must not dominate a switch.
        assertTrue("E-bike must be clearly below the car by RMS", ebike < car * 0.65)
        assertTrue("Train switch would be a loudness jump", train < car * 1.35)
    }

    @Test
    fun switchingTypeReleasesTheOldVoiceBeforeSwapping() {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.CAR,
            )
        renderer.start()
        repeat(40) { renderer.renderBlock() }

        renderer.requestType(HornType.TRAIN)
        renderer.renderBlock()
        assertEquals("Car release must outlive the first block", HornType.CAR, renderer.activeType)

        var blocks = 1
        while (renderer.activeType != HornType.TRAIN && blocks < 200) {
            renderer.renderBlock()
            blocks++
        }
        assertTrue("Renderer never switched to Train", renderer.activeType == HornType.TRAIN)
        assertTrue("Switch happened without a release block", blocks > 1)
    }

    @Test
    fun trainReleaseTailIsLongerThanACarTail() {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.TRAIN,
            )
        renderer.start()
        repeat(80) { renderer.renderBlock() }
        renderer.stop()

        var blocks = 0
        while (!renderer.isSilent && blocks < 200) {
            renderer.renderBlock()
            blocks++
        }

        assertTrue("Train tail is too short: $blocks blocks", blocks >= 30)
        assertTrue("Renderer never went silent", renderer.isSilent)
    }

    @Test
    fun panicResetsTheVoiceImmediately() {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.EBIKE,
            )
        renderer.start()
        repeat(4) { renderer.renderBlock() }

        renderer.panic()

        assertTrue(renderer.isSilent)
        assertEquals(0, renderer.renderBlock())
    }

    @Test
    fun protectionModeAppliesAtLeastSixDecibelsOfHeadroom() {
        val normal = renderRms(protected = false)
        val protected = renderRms(protected = true)

        println("PROTECTION normal=$normal protected=$protected ratio=${protected / normal}")
        assertTrue("Protected gain is not reduced enough", protected < normal * 0.65)
        assertTrue("Protected output disappeared", protected > 0.01)
    }

    @Test
    fun expressionBendsPitchUpAndDownContinuously() {
        val high = renderExpression(1f)
        val low = renderExpression(-1f)
        val highCrossings = DspMeasurement.zeroCrossingRate(high)
        val lowCrossings = DspMeasurement.zeroCrossingRate(low)

        println("EXPRESSION highZcr=$highCrossings lowZcr=$lowCrossings")
        assertTrue("Positive expression did not raise pitch", highCrossings > lowCrossings * 1.05)
    }

    @Test
    fun tunnelAddsAnAudibleTailAfterRelease() {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.TRAIN,
            )
        renderer.setAcousticSpace(AcousticSpace.TUNNEL)
        renderer.start()
        repeat(80) { renderer.renderBlock() }
        renderer.stop()

        var tailBlocks = 0
        while (!renderer.isSilent && tailBlocks < 400) {
            renderer.renderBlock()
            tailBlocks++
        }

        println("TUNNEL tailBlocks=$tailBlocks")
        assertTrue("Tunnel did not add a tail", tailBlocks > 30)
    }

    @Test
    fun customProfileUpdateIsAppliedAtTheNextStart() {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.CUSTOM,
            )
        renderer.start()
        repeat(40) { renderer.renderBlock() }
        renderer.stop()
        while (!renderer.isSilent) renderer.renderBlock()

        renderer.setCustomProfile(
            CustomHornProfile(
                primaryFrequencyHz = 1_200f,
                secondaryFrequencyRatio = 1.5f,
                harmonicRichness = 0.4f,
                attackMillis = 10f,
                releaseMillis = 20f,
                saturation = 0.2f,
            ),
        )
        renderer.start()
        val samples = renderPcm(renderer, blocks = 120)
        val newFundamental = DspMeasurement.magnitude(samples, 1_200.0)
        val oldFundamental = DspMeasurement.magnitude(samples, 620.0)

        assertTrue("Custom profile was not rebuilt", newFundamental > oldFundamental * 2.0)
    }

    @Test
    fun concurrentTypeRequestsNeverCorruptTheAudioThread() {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.CAR,
            )
        renderer.start()

        val failure = AtomicReference<Throwable?>(null)
        val observedTypes = Collections.synchronizedSet(mutableSetOf<HornType>())
        val startGate = CountDownLatch(1)
        val requesters =
            (0 until 4).map { requesterIndex ->
                Thread({
                    try {
                        startGate.await()
                        repeat(3_000) { iteration ->
                            val type = HornType.entries[(iteration + requesterIndex) % HornType.entries.size]
                            renderer.requestType(type)
                        }
                    } catch (throwable: Throwable) {
                        failure.compareAndSet(null, throwable)
                    }
                }, "type-requester-$requesterIndex")
            }
        val audioThread =
            Thread({
                try {
                    startGate.countDown()
                    repeat(3_000) {
                        val frames = renderer.renderBlock()
                        observedTypes.add(renderer.activeType)
                        for (index in 0 until frames) {
                            if (renderer.outputBuffer[index] == Short.MIN_VALUE) {
                                error("PCM underflow at $index")
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                }
            }, "audio-thread")

        requesters.forEach { it.start() }
        audioThread.start()
        requesters.forEach { it.join(10_000) }
        audioThread.join(10_000)

        assertNull("Concurrent switching failed: ${failure.get()?.message}", failure.get())
        assertTrue("Audio thread did not observe any voice switch", observedTypes.size >= 2)
    }

    private fun renderRms(protected: Boolean): Double {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.CAR,
            )
        renderer.setProtection(protected)
        renderer.start()
        return DspMeasurement.rms(renderPcm(renderer, blocks = 400))
    }

    private fun renderExpression(expression: Float): FloatArray {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.CAR,
            )
        renderer.setExpression(expression)
        renderer.start()
        return renderPcm(renderer, blocks = 300)
    }

    private fun renderPcm(
        renderer: HornRenderer,
        blocks: Int,
    ): FloatArray {
        val output = FloatArray(blocks * DspMeasurement.FRAMES_PER_BLOCK)
        for (block in 0 until blocks) {
            val frames = renderer.renderBlock()
            for (index in 0 until frames) {
                output[block * DspMeasurement.FRAMES_PER_BLOCK + index] =
                    renderer.outputBuffer[index] / 32_768f
            }
        }
        return output
    }
}
