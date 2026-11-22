package com.industrial.horn.audio

import kotlinx.coroutines.flow.Flow

/**
 * Contract for a press-and-hold horn playback engine.
 *
 * The interface deliberately hides the rendering backend so the real-time synthesizer can later
 * be replaced by Oboe or a pre-decoded WAV engine without touching the presentation layer.
 * Implementations must be safe to call from the main thread; all rendering has to happen on a
 * dedicated high-priority audio thread.
 */
interface HornAudioEngine {
    /** Asynchronous, non-fatal engine events. Failures while rendering are reported here. */
    val events: Flow<HornAudioEvent>

    /** Warms up the output path. Safe to call repeatedly and safe to skip. */
    fun prepare()

    /**
     * Publishes the desired voice without touching the audio thread's active state. A live voice
     * releases to silence before the new strategy is swapped in.
     */
    fun selectHorn(type: HornType)

    fun setExpression(expression: Float)

    fun setAcousticSpace(space: AcousticSpace)

    fun setProtection(enabled: Boolean)

    fun setCustomProfile(profile: CustomHornProfile)

    /**
     * Selects the psychoacoustic output stage (contract 00 §5). Production engines MUST override
     * this method; the default keeps test stubs compiling and behaves as `SPEAKER_VBS`.
     */
    fun setOutputMode(mode: OutputMode) = Unit

    fun telemetrySnapshot(): AudioTelemetry

    /** Fills [destination] with the newest real PCM samples; returns the count written. */
    fun readWaveform(destination: FloatArray): Int

    /**
     * Stereo waveform mirror (contract 00 §5): newest samples per channel in chronological order.
     * Production engines MUST override this method; the default keeps test stubs compiling and
     * reports zero samples.
     */
    fun readWaveformStereo(
        left: FloatArray,
        right: FloatArray,
    ): Int = 0

    /** Requests audible output. Returns false only when the engine has already been released. */
    fun start(): Boolean

    /** Requests a short, click-free release. The call itself never blocks on the audio thread. */
    fun stop()

    /** Immediately silences the output, e.g. when the app loses foreground or audio focus. */
    fun panicStop()

    /** Permanently releases the native output. The instance cannot be restarted afterwards. */
    fun release()
}

sealed interface HornAudioEvent {
    data class Failure(
        val cause: Throwable,
    ) : HornAudioEvent
}
