package com.industrial.horn.audio

/**
 * Zero-allocation mono Schroeder reverb: four damped comb filters followed by two all-pass
 * diffusers and a per-space pre-delay. The output is mixed with the dry signal; the renderer's
 * final soft limiter keeps the added energy bounded.
 */
class SpatialReverb(
    private val sampleRate: Int,
) {
    private val combDelays =
        intArrayOf(
            scaleDelay(sampleRate, 1_116),
            scaleDelay(sampleRate, 1_188),
            scaleDelay(sampleRate, 1_277),
            scaleDelay(sampleRate, 1_356),
        )
    private val combBuffers = Array(combDelays.size) { FloatArray(combDelays[it]) }
    private val combIndices = IntArray(combDelays.size)
    private val combDampingState = FloatArray(combDelays.size)

    private val allPassDelays =
        intArrayOf(
            scaleDelay(sampleRate, 556),
            scaleDelay(sampleRate, 441),
        )
    private val allPassBuffers = Array(allPassDelays.size) { FloatArray(allPassDelays[it]) }
    private val allPassIndices = IntArray(allPassDelays.size)

    private val preDelayBuffer = FloatArray(scaleDelay(sampleRate, 2_000))
    private var preDelayIndex = 0

    private var space = AcousticSpace.STREET
    private var preDelaySamples = samplesForMillis(sampleRate, space.preDelayMillis)
    private var wetMix = space.wetMix
    private var feedback = space.feedback
    private var damping = space.damping

    fun setSpace(space: AcousticSpace) {
        if (this.space == space) return
        this.space = space
        preDelaySamples =
            samplesForMillis(sampleRate, space.preDelayMillis).coerceIn(
                0,
                preDelayBuffer.size - 1,
            )
        wetMix = space.wetMix
        feedback = space.feedback
        damping = space.damping
    }

    fun process(
        buffer: FloatArray,
        frameCount: Int,
    ) {
        require(frameCount in 0..buffer.size) { "frameCount outside buffer" }

        for (sampleIndex in 0 until frameCount) {
            val dry = buffer[sampleIndex]
            preDelayBuffer[preDelayIndex] = dry
            val delayedIndex = Math.floorMod(preDelayIndex - preDelaySamples, preDelayBuffer.size)
            val delayed = preDelayBuffer[delayedIndex]
            preDelayIndex++
            if (preDelayIndex >= preDelayBuffer.size) preDelayIndex = 0

            var wet = 0f
            for (comb in combBuffers.indices) {
                val bufferForComb = combBuffers[comb]
                val index = combIndices[comb]
                val delayedComb = bufferForComb[index]
                combDampingState[comb] =
                    delayedComb * (1f - damping) + combDampingState[comb] * damping
                bufferForComb[index] =
                    (delayed + combDampingState[comb] * feedback).coerceIn(-4f, 4f)
                combIndices[comb] = if (index + 1 >= bufferForComb.size) 0 else index + 1
                wet += delayedComb
            }
            wet *= 0.25f

            for (allPass in allPassBuffers.indices) {
                val bufferForAllPass = allPassBuffers[allPass]
                val index = allPassIndices[allPass]
                val buffered = bufferForAllPass[index]
                val output = buffered - wet
                bufferForAllPass[index] = wet + buffered * 0.5f
                allPassIndices[allPass] =
                    if (index + 1 >= bufferForAllPass.size) 0 else index + 1
                wet = output
            }

            buffer[sampleIndex] = dry * (1f - wetMix * 0.5f) + wet * wetMix
        }
    }

    fun reset() {
        combBuffers.forEach { it.fill(0f) }
        combIndices.fill(0)
        combDampingState.fill(0f)
        allPassBuffers.forEach { it.fill(0f) }
        allPassIndices.fill(0)
        preDelayBuffer.fill(0f)
        preDelayIndex = 0
    }

    private companion object {
        fun scaleDelay(
            sampleRate: Int,
            delayAt44k: Int,
        ): Int = (delayAt44k * sampleRate / 44_100).coerceAtLeast(1)

        fun samplesForMillis(
            sampleRate: Int,
            millis: Float,
        ): Int = (sampleRate * millis / 1_000f).toInt().coerceAtLeast(0)
    }
}
