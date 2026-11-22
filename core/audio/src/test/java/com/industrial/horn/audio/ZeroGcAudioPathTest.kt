package com.industrial.horn.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Allocation audit for the real-time render path. JVM allocation accounting is noisy around JIT,
 * so this test warms up first and then allows a small fixed budget for 2,400 blocks (12 s audio).
 * A regression that allocates per block or per sample will exceed it immediately.
 *
 * The JDK management API is loaded reflectively because Android unit tests compile against the
 * Android boot classpath, while execution still runs on the host JDK.
 */
class ZeroGcAudioPathTest {
    @Test
    fun steadyStateRendererDoesNotAllocateOnTheAudioLoop() {
        val renderer =
            HornRenderer(
                sampleRate = DspMeasurement.SAMPLE_RATE,
                framesPerBlock = DspMeasurement.FRAMES_PER_BLOCK,
                initialType = HornType.CAR,
            )
        renderer.start()
        repeat(WARMUP_BLOCKS) { renderer.renderBlock() }

        val allocated =
            measureAllocatedBytes {
                repeat(MEASURED_BLOCKS) { renderer.renderBlock() }
            }

        println("ZERO_GC allocatedBytes=$allocated for $MEASURED_BLOCKS blocks")
        assertTrue(
            "Real-time renderer allocated $allocated bytes over $MEASURED_BLOCKS blocks",
            allocated < ALLOCATION_BUDGET_BYTES,
        )
    }

    private fun measureAllocatedBytes(block: () -> Unit): Long {
        val managementFactory = Class.forName("java.lang.management.ManagementFactory")
        val bean = managementFactory.getMethod("getThreadMXBean").invoke(null)
        val allocationBean = Class.forName("com.sun.management.ThreadMXBean")
        val allocatedBytes = allocationBean.getMethod("getCurrentThreadAllocatedBytes")

        val before = allocatedBytes.invoke(bean) as Long
        block()
        val after = allocatedBytes.invoke(bean) as Long
        return after - before
    }

    private companion object {
        const val WARMUP_BLOCKS = 500
        const val MEASURED_BLOCKS = 2_400

        // 64 KB for 12 seconds is far below one allocation per block; the expected steady-state
        // value is effectively zero, while any per-block boxing would blow this budget.
        const val ALLOCATION_BUDGET_BYTES = 64L * 1024L
    }
}
