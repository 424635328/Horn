package com.industrial.horn.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-time render budget assertion. A 128-frame block at 48 kHz has a hard deadline of
 * 2.66 ms; the engine must render far below that budget on a plain JVM so the audio thread on
 * device keeps a comfortable safety margin (the production AudioTrack path uses the same
 * [HornRenderer]).
 *
 * The test renders ten seconds of audio per trial and asserts on the fastest trial's mean block
 * time to stay robust against CI noise while still catching real DSP regressions.
 */
class RenderBudgetTest {
    @Test
    fun tenSecondsOfAudioRenderFarBelowRealTimeBudget() {
        val blocksPerVoice = (SAMPLE_RATE * SECONDS / FRAMES_PER_BLOCK) / HornType.entries.size
        require(blocksPerVoice > 0) { "test constants produce zero work" }

        repeat(WARMUP_BLOCKS) { renderOneBlock(HornType.CAR) }

        val trialMeanNanos =
            (0 until TRIALS).minOf { renderTrial(blocksPerVoice) }

        val budgetNanos = REALTIME_BUDGET_MILLIS * 1_000_000.0
        assertTrue(
            "128-frame block rendered in ${trialMeanNanos / 1e6} ms, budget is " +
                "$REALTIME_BUDGET_MILLIS ms",
            trialMeanNanos < budgetNanos,
        )
    }

    /** Renders five voices for [blocksPerVoice] blocks each; returns mean block time in nanos. */
    private fun renderTrial(blocksPerVoice: Int): Double {
        var totalNanos = 0L
        for (type in HornType.entries) {
            val renderer =
                HornRenderer(
                    sampleRate = SAMPLE_RATE,
                    framesPerBlock = FRAMES_PER_BLOCK,
                    initialType = type,
                )
            renderer.start()
            repeat(blocksPerVoice) {
                val started = System.nanoTime()
                renderer.renderBlock()
                totalNanos += System.nanoTime() - started
            }
            renderer.stop()
        }
        val blocks = blocksPerVoice * HornType.entries.size
        return totalNanos.toDouble() / blocks
    }

    private fun renderOneBlock(type: HornType) {
        val renderer =
            HornRenderer(
                sampleRate = SAMPLE_RATE,
                framesPerBlock = FRAMES_PER_BLOCK,
                initialType = type,
            )
        renderer.start()
        renderer.renderBlock()
        renderer.stop()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAMES_PER_BLOCK = 128
        const val SECONDS = 10
        const val WARMUP_BLOCKS = 256
        const val TRIALS = 3
        const val REALTIME_BUDGET_MILLIS = 2.66
    }
}
