package com.industrial.horn.presentation

import kotlin.math.min

/**
 * Frame-interval meter for the cold-start animation.
 *
 * The startup-animation discipline asks for a self-reported metric at completion (median / p95 /
 * slow-frame count) instead of a visual claim that "it feels smooth". The meter is a fixed
 * ring buffer: [record] allocates nothing, and only [summary] copies the samples once, when the
 * animation is already over. Invalid deltas (non-finite, ≤ 0, or > 1 s, i.e. a suspend/resume)
 * are dropped so a backgrounded process cannot poison the median.
 */
class StartupFrameMeter(
    private val capacity: Int = 96,
) {
    private val samples = FloatArray(capacity.coerceAtLeast(1))
    private var count = 0
    private var cursor = 0

    fun record(frameMillis: Float) {
        if (!frameMillis.isFinite() || frameMillis <= 0f || frameMillis > 1_000f) return
        samples[cursor] = frameMillis
        cursor = (cursor + 1) % samples.size
        count = min(count + 1, samples.size)
    }

    fun median(): Float = percentile(0.50f)

    fun p95(): Float = percentile(0.95f)

    fun slowFrames(thresholdMillis: Float = 33.4f): Int {
        if (count == 0) return 0
        var slow = 0
        val limit = min(count, samples.size)
        for (index in 0 until limit) {
            if (samples[index] > thresholdMillis) slow++
        }
        return slow
    }

    fun summary(): String =
        if (count == 0) {
            "startup frames: no samples"
        } else {
            "startup frames: n=$count median=${"%.1f".format(median())}ms " +
                "p95=${"%.1f".format(p95())}ms slow(>33.4ms)=${slowFrames()}"
        }

    private fun percentile(fraction: Float): Float {
        if (count == 0) return 0f
        val sorted = samples.copyOf(count)
        sorted.sort()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
