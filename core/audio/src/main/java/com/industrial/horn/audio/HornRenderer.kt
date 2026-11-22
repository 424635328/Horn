package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.softSaturate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Pure-JVM real-time renderer used by the AudioTrack backend.
 *
 * Threading contract:
 *  - [requestType], [setExpression], [setAcousticSpace], [setProtection] and [setCustomProfile]
 *    may be called from any thread; each publishes only an atomic value.
 *  - every other mutating method and [renderBlock] belong to the single audio thread.
 *
 * A type change never swaps a waveform underneath a live sample. The old voice receives its
 * release envelope, reaches exact silence, and only then is the new voice reset and attacked.
 * This makes rapid UI switching, including a switch during a held honk, click-free and free of
 * data races. All normal render buffers are preallocated; only a deliberate Custom profile
 * change rebuilds its voice at a silent boundary.
 */
class HornRenderer(
    val sampleRate: Int,
    val framesPerBlock: Int,
    initialType: HornType = HornType.CAR,
) {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8000 Hz" }
        require(framesPerBlock > 0) { "framesPerBlock must be positive" }
    }

    private val requestedType = AtomicReference(initialType)
    private val expressionBits = AtomicInteger(java.lang.Float.floatToRawIntBits(0f))
    private val acousticSpace = AtomicReference(AcousticSpace.STREET)
    private val protectionEnabled = AtomicInteger(0)
    private val customProfile = AtomicReference(CustomHornProfile.DEFAULT)

    private val voices =
        Array(HornType.entries.size) { index ->
            HornVoiceCatalog.createVoice(HornType.entries[index], sampleRate)
        }
    private val pcmRenderer = PcmRenderer(maxFrames = framesPerBlock)
    private val reverb = SpatialReverb(sampleRate)
    private val output = ShortArray(framesPerBlock)
    private val floatBlock = FloatArray(framesPerBlock)

    private var appliedCustomProfile = CustomHornProfile.DEFAULT
    private var currentType = initialType
    private var pendingType: HornType? = null
    private var playing = false
    private var previousEnvelopeActive = false
    private var tailPending = false
    private var tailBlocksRemaining = 0

    val outputBuffer: ShortArray
        get() = output

    val activeType: HornType
        get() = currentType

    val isPlaying: Boolean
        get() = playing

    val isSilent: Boolean
        get() =
            pendingType == null &&
                !tailPending &&
                voice(currentType).envelope.isSilent &&
                tailBlocksRemaining <= 0

    fun requestType(type: HornType) {
        requestedType.set(type)
    }

    fun setExpression(expression: Float) {
        expressionBits.set(
            java.lang.Float.floatToRawIntBits(expression.coerceIn(-1f, 1f)),
        )
    }

    fun setAcousticSpace(space: AcousticSpace) {
        acousticSpace.set(space)
    }

    fun setProtection(enabled: Boolean) {
        protectionEnabled.set(if (enabled) 1 else 0)
    }

    fun setCustomProfile(profile: CustomHornProfile) {
        customProfile.set(profile.sanitized())
    }

    fun start() {
        if (playing) return
        playing = true
        tailBlocksRemaining = 0
        tailPending = false
        applyPendingCustomProfile()

        val desired = requestedType.get()
        val currentVoice = voice(currentType)
        if (desired != currentType && currentVoice.envelope.isSilent) {
            switchTo(desired)
            pendingType = null
        } else if (desired != currentType) {
            pendingType = desired
            currentVoice.envelope.noteOff()
            return
        }

        val voice = voice(currentType)
        if (voice.envelope.isSilent) {
            voice.synthesizer.reset()
        }
        voice.synthesizer.setExpression(currentExpression())
        voice.envelope.noteOn()
    }

    fun stop() {
        playing = false
        tailPending = true
        voice(currentType).envelope.noteOff()
    }

    fun panic() {
        playing = false
        pendingType = null
        tailBlocksRemaining = 0
        tailPending = false
        previousEnvelopeActive = false
        val voice = voice(currentType)
        voice.envelope.reset()
        voice.synthesizer.reset()
        reverb.reset()
    }

    /**
     * Advances one block. Returns the number of valid frames in [outputBuffer]; zero means the
     * voice and spatial tail are fully silent and the AudioTrack can be paused.
     */
    fun renderBlock(): Int {
        reconcileType()
        applyPendingCustomProfile()

        val voice = voice(currentType)
        val active = voice.envelope.isActive
        if (!active && (previousEnvelopeActive || tailPending)) {
            tailBlocksRemaining = acousticSpace.get().tailBlocks
            previousEnvelopeActive = false
            tailPending = false
        }

        if (!active) {
            if (tailBlocksRemaining <= 0) return 0
            floatBlock.fill(0f)
            renderSpatialTail()
            tailBlocksRemaining--
            return framesPerBlock
        }

        previousEnvelopeActive = true
        voice.synthesizer.setExpression(currentExpression())
        val gain = voice.makeupGain * if (protectionEnabled.get() == 1) PROTECTION_GAIN else 1f
        pcmRenderer.render(
            synthesizer = voice.synthesizer,
            envelope = voice.envelope,
            output = output,
            frameCount = framesPerBlock,
            voiceGain = gain,
        )
        processPcmThroughReverb()
        return framesPerBlock
    }

    private fun renderSpatialTail() {
        reverb.setSpace(acousticSpace.get())
        reverb.process(floatBlock, framesPerBlock)
        writeLimitedPcm()
    }

    private fun processPcmThroughReverb() {
        for (index in 0 until framesPerBlock) {
            floatBlock[index] = output[index] / 32_768f
        }
        reverb.setSpace(acousticSpace.get())
        reverb.process(floatBlock, framesPerBlock)
        writeLimitedPcm()
    }

    private fun writeLimitedPcm() {
        for (index in 0 until framesPerBlock) {
            val limited =
                softSaturate(floatBlock[index].toDouble(), REVERB_LIMITER_DRIVE)
                    .coerceIn(-HARD_CEILING, HARD_CEILING)
            output[index] =
                (limited * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(PCM_MIN, PCM_MAX)
                    .toShort()
        }
    }

    private fun currentExpression(): Float = java.lang.Float.intBitsToFloat(expressionBits.get())

    private fun reconcileType() {
        val desired = requestedType.get()

        if (desired != currentType) {
            pendingType = desired
            if (voice(currentType).envelope.isSilent) {
                switchTo(desired)
                pendingType = null
                if (playing) voice(currentType).envelope.noteOn()
            } else {
                voice(currentType).envelope.noteOff()
            }
        } else if (pendingType != null) {
            // The user switched back to the active type before its release finished.
            pendingType = null
            if (playing) voice(currentType).envelope.noteOn()
        }

        val pending = pendingType ?: return
        if (voice(currentType).envelope.isSilent) {
            switchTo(pending)
            pendingType = null
            if (playing) voice(currentType).envelope.noteOn()
        }
    }

    private fun switchTo(type: HornType) {
        if (type == HornType.CUSTOM) applyPendingCustomProfile()
        val next = voice(type)
        next.reset()
        currentType = type
        next.synthesizer.setExpression(currentExpression())
    }

    private fun applyPendingCustomProfile() {
        val requested = customProfile.get()
        if (requested == appliedCustomProfile) return
        if (voice(HornType.CUSTOM).envelope.isActive) return
        val safe = requested.sanitized()
        voices[HornType.CUSTOM.ordinal] =
            HornVoiceCatalog.createVoice(HornType.CUSTOM, sampleRate, safe)
        appliedCustomProfile = requested
    }

    private fun voice(type: HornType): HornVoice = voices[type.ordinal]

    private companion object {
        const val PROTECTION_GAIN = 0.501f
        const val REVERB_LIMITER_DRIVE = 1.0
        const val HARD_CEILING = 0.98
        val PCM_MAX = (HARD_CEILING * Short.MAX_VALUE).roundToInt()
        val PCM_MIN = -PCM_MAX
    }
}
