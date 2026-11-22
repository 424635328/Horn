package com.industrial.horn.audio

import kotlinx.coroutines.flow.Flow

/**
 * Foreground-safe horn session. It couples audio focus, synthesis and haptics behind one
 * idempotent, testable boundary.
 */
interface HornSession {
    val events: Flow<HornSessionEvent>

    fun start(type: HornType): HornStartResult

    fun setExpression(expression: Float)

    fun setAcousticSpace(space: AcousticSpace)

    fun setProtection(enabled: Boolean)

    fun setCustomProfile(profile: CustomHornProfile)

    fun telemetrySnapshot(): AudioTelemetry

    fun readWaveform(destination: FloatArray): Int

    fun stop()

    fun panicStop()

    fun release()
}

enum class HornStartResult {
    GRANTED,
    FOCUS_DENIED,
    ENGINE_UNAVAILABLE,
}

sealed interface HornSessionEvent {
    data class Interrupted(
        val reason: InterruptionReason,
    ) : HornSessionEvent

    data class EngineFailed(
        val cause: Throwable?,
    ) : HornSessionEvent
}

enum class InterruptionReason {
    AUDIO_FOCUS_LOST,
    AUDIO_BECOMING_NOISY,
}
