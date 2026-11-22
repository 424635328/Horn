package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.DspConstants
import com.industrial.horn.audio.dsp.LoudnessTable
import com.industrial.horn.audio.dsp.OnePoleSmoother
import com.industrial.horn.audio.dsp.OutputModeChain
import com.industrial.horn.audio.dsp.SceneSpecCatalog
import com.industrial.horn.audio.dsp.StereoOutputStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-JVM real-time stereo renderer used by the AudioTrack backend. Pipeline order is frozen by
 * docs/spec/research/00 §4:
 *
 * ```
 * physical exciter (band-limited, transients, nonlinear steepening)
 *   -> multi-horn stereo placement (inside the synths, amplitude-dominant)
 *   -> master envelope / voice gain
 *   -> scene processing (ISM early reflections -> FDN late tail -> common colouring
 *      -> ISO 9613-1 atmosphere -> dry/wet + width)
 *   -> output mode (speaker VBS / headphone width chain, equal-power crossfade)
 *   -> loudness feed-forward G (03 §4) -> ear protection
 *   -> StereoOutputStage (true peak -> look-ahead soft limiter -> tanh(1.0) -> +/-0.98)
 *   -> interleaved Float32 output
 * ```
 *
 * Threading contract:
 *  - [requestType], [setExpression], [setAcousticSpace], [setProtection], [setCustomProfile] and
 *    [setOutputMode] may be called from any thread; each publishes only an atomic value.
 *  - every other mutating method and [renderBlock] belong to the single audio thread.
 *
 * A type change never swaps a waveform underneath a live sample: the old voice receives its
 * release envelope, reaches exact silence, and only then is the new voice reset and attacked.
 * All render buffers are preallocated; only a deliberate Custom profile change rebuilds its voice
 * at a silent boundary.
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
    private val outputMode = AtomicReference(OutputMode.SPEAKER_VBS)

    private val voices =
        Array(HornType.entries.size) { index ->
            HornVoiceCatalog.createVoice(HornType.entries[index], sampleRate)
        }
    private val scene = SceneEngine(sampleRate)
    private val modeChain = OutputModeChain(sampleRate)
    private val outputStage = StereoOutputStage()
    private val gainSmoother = OnePoleSmoother(sampleRate, GAIN_SMOOTHING_MILLIS, 0f)
    private val output = FloatArray(2 * framesPerBlock)

    private var appliedCustomProfile = CustomHornProfile.DEFAULT
    private var currentType = initialType
    private var pendingType: HornType? = null
    private var playing = false
    private var previousEnvelopeActive = false
    private var tailPending = false
    private var tailBlocksRemaining = 0

    /** Interleaved stereo output block (`buffer[2i]` = left, `buffer[2i+1]` = right). */
    val outputBuffer: FloatArray
        get() = output

    val activeType: HornType
        get() = currentType

    val isPlaying: Boolean
        get() = playing

    val currentOutputMode: OutputMode
        get() = outputMode.get()

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

    fun setOutputMode(mode: OutputMode) {
        outputMode.set(mode)
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
            noteOff(currentVoice)
            return
        }

        val voice = voice(currentType)
        if (voice.envelope.isSilent) {
            voice.synthesizer.reset()
        }
        voice.synthesizer.setExpression(currentExpression())
        noteOn(voice)
    }

    fun stop() {
        playing = false
        tailPending = true
        noteOff(voice(currentType))
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
        scene.reset()
        modeChain.reset()
        outputStage.reset()
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
            tailBlocksRemaining = tailBlocks()
            previousEnvelopeActive = false
            tailPending = false
        }

        if (!active) {
            if (tailBlocksRemaining <= 0) return 0
            for (index in output.indices) output[index] = 0f
            tailBlocksRemaining--
            renderThroughPipeline(envelope = null)
            return framesPerBlock
        }

        previousEnvelopeActive = true
        voice.synthesizer.setExpression(currentExpression())
        voice.synthesizer.render(output, framesPerBlock)
        renderThroughPipeline(envelope = voice.envelope)
        return framesPerBlock
    }

    /** Scene -> output mode -> loudness G -> protection -> hard-bounded output stage. */
    private fun renderThroughPipeline(envelope: AttackReleaseEnvelope?) {
        scene.setSpace(acousticSpace.get())
        scene.process(output, framesPerBlock)

        val mode = outputMode.get()
        modeChain.setMode(mode)
        modeChain.setInjection(currentInjection())
        modeChain.process(output, framesPerBlock)

        val targetGain = currentGain(mode)
        if (envelope == null) {
            for (frame in 0 until framesPerBlock) {
                val gain = gainSmoother.next(targetGain)
                output[2 * frame] *= gain
                output[2 * frame + 1] *= gain
            }
        } else {
            for (frame in 0 until framesPerBlock) {
                val gain = gainSmoother.next(targetGain) * envelope.nextSample()
                output[2 * frame] *= gain
                output[2 * frame + 1] *= gain
            }
        }
        outputStage.process(output, framesPerBlock)
    }

    private fun currentGain(mode: OutputMode): Float =
        LoudnessTable.totalGain(
            type = currentType,
            space = acousticSpace.get(),
            mode = mode,
            protectionActive = protectionEnabled.get() == 1,
        )

    private fun currentInjection(): Float = LoudnessTable.vbsInjection(currentType, appliedCustomProfile.primaryFrequencyHz)

    private fun tailBlocks(): Int {
        val tailMillis = SceneSpecCatalog.spec(acousticSpace.get()).tailMillis
        val tailFrames = (sampleRate * tailMillis / 1_000f).toInt()
        return ((tailFrames + framesPerBlock - 1) / framesPerBlock).coerceAtLeast(1)
    }

    private fun currentExpression(): Float = java.lang.Float.intBitsToFloat(expressionBits.get())

    private fun noteOn(voice: HornVoice) {
        voice.envelope.noteOn()
        val synth = voice.synthesizer
        if (synth is NoteAwareSynthesizer) synth.setNoteActive(true)
    }

    private fun noteOff(voice: HornVoice) {
        voice.envelope.noteOff()
        val synth = voice.synthesizer
        if (synth is NoteAwareSynthesizer) synth.setNoteActive(false)
    }

    private fun reconcileType() {
        val desired = requestedType.get()

        if (desired != currentType) {
            pendingType = desired
            if (voice(currentType).envelope.isSilent) {
                switchTo(desired)
                pendingType = null
                if (playing) noteOn(voice(currentType))
            } else {
                noteOff(voice(currentType))
            }
        } else if (pendingType != null) {
            // The user switched back to the active type before its release finished.
            pendingType = null
            if (playing) noteOn(voice(currentType))
        }

        val pending = pendingType ?: return
        if (voice(currentType).envelope.isSilent) {
            switchTo(pending)
            pendingType = null
            if (playing) noteOn(voice(currentType))
        }
    }

    private fun switchTo(type: HornType) {
        if (type == HornType.CUSTOM) applyPendingCustomProfile()
        val next = voice(type)
        next.reset()
        currentType = type
        next.synthesizer.setExpression(currentExpression())
        gainSmoother.setCurrent(currentGain(outputMode.get()))
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
        const val GAIN_SMOOTHING_MILLIS = 20f
    }
}
