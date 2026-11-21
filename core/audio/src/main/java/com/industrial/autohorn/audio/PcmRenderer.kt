package com.industrial.autohorn.audio

import com.industrial.autohorn.audio.dsp.softSaturate
import kotlin.math.roundToInt

/**
 * Final gain stage shared by every voice.
 *
 * The envelope, per-voice equal-loudness makeup gain and master gain are applied first, then a
 * single soft limiter (`tanh`) and a hard [-0.98, 0.98] ceiling guarantee that no combination of
 * chord density, saturation or makeup gain can clip the 16-bit output. The work buffer can be
 * preallocated by the engine so the first audio block does not allocate.
 */
class PcmRenderer(
    private val masterGain: Float = DEFAULT_MASTER_GAIN,
    maxFrames: Int = 0,
) {
    init {
        require(masterGain in 0f..1f) { "masterGain must be in 0..1" }
        require(maxFrames >= 0) { "maxFrames must be non-negative" }
    }

    private var workBuffer = FloatArray(maxFrames)

    fun render(
        synthesizer: HornSynthesizer,
        envelope: AttackReleaseEnvelope,
        output: ShortArray,
        frameCount: Int,
        voiceGain: Float = 1f,
    ) {
        require(frameCount in 0..output.size) { "frameCount outside output buffer" }
        require(voiceGain in 0f..1f) { "voiceGain must be in 0..1" }

        if (workBuffer.size < frameCount) {
            workBuffer = FloatArray(frameCount)
        }

        synthesizer.render(workBuffer, frameCount)
        for (index in 0 until frameCount) {
            val dry = workBuffer[index] * envelope.nextSample() * masterGain * voiceGain
            val limited =
                softSaturate(dry.toDouble(), LIMITER_DRIVE)
                    .coerceIn(-HARD_CEILING.toDouble(), HARD_CEILING.toDouble())
            output[index] =
                (limited * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(PCM_MIN, PCM_MAX)
                    .toShort()
        }
    }

    companion object {
        const val DEFAULT_MASTER_GAIN = 0.9f
        const val HARD_CEILING = 0.98f
        const val LIMITER_DRIVE = 1.1

        private val PCM_MAX = (HARD_CEILING * Short.MAX_VALUE).roundToInt()
        private val PCM_MIN = -PCM_MAX
    }
}
