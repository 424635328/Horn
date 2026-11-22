package com.industrial.horn.benchmark

import com.industrial.horn.audio.HornRenderer
import com.industrial.horn.audio.HornType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ten seconds of real-time DSP across all five voices. The renderer runs on a plain JVM here; the
 * production path is the same HornRenderer used by the AudioTrack fallback.
 */
class DspPerformanceTest {
    @Test
    fun tenSecondsOfFiveVoiceAudioRendersManyTimesFasterThanRealTime() {
        val blocksPerVoice = (SAMPLE_RATE * SECONDS / FRAMES_PER_BLOCK) / HornType.entries.size
        var samplesRendered = 0L

        // Warm up class loading, JIT and the first allocation of each voice.
        repeat(100) { warmupBlock() }

        val started = System.nanoTime()
        for (type in HornType.entries) {
            val renderer =
                HornRenderer(
                    sampleRate = SAMPLE_RATE,
                    framesPerBlock = FRAMES_PER_BLOCK,
                    initialType = type,
                )
            renderer.start()
            repeat(blocksPerVoice) {
                val frames = renderer.renderBlock()
                samplesRendered += frames
                for (index in 0 until frames) {
                    require(renderer.outputBuffer[index].toInt() in Short.MIN_VALUE..Short.MAX_VALUE)
                }
            }
            renderer.stop()
        }
        val elapsedNanos = System.nanoTime() - started
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        val realtimeSeconds = samplesRendered.toDouble() / SAMPLE_RATE

        println(
            "DSP_BENCHMARK samples=$samplesRendered audio=${"%.2f".format(realtimeSeconds)}s " +
                "render=${"%.3f".format(elapsedSeconds)}s ratio=${"%.1f".format(realtimeSeconds / elapsedSeconds)}x",
        )
        assertTrue("Renderer produced no audio", samplesRendered > 0)
        assertTrue(
            "DSP missed real-time: rendered ${realtimeSeconds}s in ${elapsedSeconds}s",
            elapsedSeconds < realtimeSeconds,
        )
    }

    private fun warmupBlock() {
        val renderer =
            HornRenderer(
                sampleRate = SAMPLE_RATE,
                framesPerBlock = FRAMES_PER_BLOCK,
                initialType = HornType.CAR,
            )
        renderer.start()
        renderer.renderBlock()
        renderer.stop()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAMES_PER_BLOCK = 240
        const val SECONDS = 10
    }
}
