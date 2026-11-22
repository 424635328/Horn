package com.industrial.horn.audio

import androidx.annotation.Keep
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Oboe / AAudio backend. Native DSP owns the real-time path; Kotlin only publishes atomic
 * parameters and reads telemetry. On devices where AAudio cannot open, [HornAudioEngineFactory]
 * wraps this engine with the zero-GC AudioTrack fallback.
 */
@Keep
class OboeHornAudioEngine : HornAudioEngine {
    private val lock = Any()
    private val _events =
        MutableSharedFlow<HornAudioEvent>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: Flow<HornAudioEvent> = _events.asSharedFlow()

    private val telemetryScratch = FloatArray(6)
    private val released = AtomicBoolean(false)

    @Volatile
    private var handle: Long

    init {
        check(isSupported()) { "Oboe native library is not available" }
        handle = nativeCreate()
        check(handle != 0L) { "Oboe native engine could not be created" }
    }

    override fun prepare() {
        val current = handle
        if (current == 0L) return
        if (!nativePrepare(current)) {
            _events.tryEmit(HornAudioEvent.Failure(IllegalStateException("Oboe stream open failed")))
        }
    }

    override fun selectHorn(type: HornType) {
        val current = handle
        if (current != 0L) nativeSetType(current, type.ordinal)
    }

    override fun setExpression(expression: Float) {
        val current = handle
        if (current != 0L) nativeSetExpression(current, expression.coerceIn(-1f, 1f))
    }

    override fun setAcousticSpace(space: AcousticSpace) {
        val current = handle
        if (current != 0L) nativeSetSpace(current, space.ordinal)
    }

    override fun setProtection(enabled: Boolean) {
        val current = handle
        if (current != 0L) nativeSetProtection(current, enabled)
    }

    override fun setCustomProfile(profile: CustomHornProfile) {
        val current = handle
        if (current == 0L) return
        val safe = profile.sanitized()
        nativeSetCustomProfile(
            current,
            safe.primaryFrequencyHz,
            safe.secondaryFrequencyRatio,
            safe.dualTone,
            safe.harmonicRichness,
            safe.attackMillis,
            safe.releaseMillis,
            safe.saturation,
        )
    }

    override fun telemetrySnapshot(): AudioTelemetry {
        val current = handle
        if (current == 0L) return AudioTelemetry(engineName = ENGINE_NAME)
        nativeReadTelemetry(current, telemetryScratch)
        return AudioTelemetry(
            engineName = ENGINE_NAME,
            rms = telemetryScratch[0],
            latencyMs = telemetryScratch[1],
            xrunCount = telemetryScratch[2].toLong(),
            renderTimeMicros = telemetryScratch[3].toInt(),
            isPlaying = telemetryScratch[4] > 0.5f,
        )
    }

    override fun readWaveform(destination: FloatArray): Int {
        val current = handle
        if (current == 0L) return 0
        return nativeReadWaveform(current, destination)
    }

    override fun start(): Boolean {
        val current = handle
        if (current == 0L) return false
        val started = nativeStart(current)
        if (!started) {
            _events.tryEmit(HornAudioEvent.Failure(IllegalStateException("Oboe start failed")))
        }
        return started
    }

    override fun stop() {
        val current = handle
        if (current != 0L) nativeStop(current)
    }

    override fun panicStop() {
        val current = handle
        if (current != 0L) nativePanic(current)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val current: Long
        synchronized(lock) {
            current = handle
            handle = 0L
        }
        if (current != 0L) nativeRelease(current)
    }

    companion object {
        const val ENGINE_NAME = "Oboe C++"

        private val libraryLoaded: Boolean =
            runCatching {
                System.loadLibrary("horn")
            }.isSuccess

        fun isSupported(): Boolean = libraryLoaded
    }

    @Keep
    private external fun nativeCreate(): Long

    @Keep
    private external fun nativePrepare(handle: Long): Boolean

    @Keep
    private external fun nativeStart(handle: Long): Boolean

    @Keep
    private external fun nativeStop(handle: Long)

    @Keep
    private external fun nativePanic(handle: Long)

    @Keep
    private external fun nativeRelease(handle: Long)

    @Keep
    private external fun nativeSetType(
        handle: Long,
        type: Int,
    )

    @Keep
    private external fun nativeSetExpression(
        handle: Long,
        expression: Float,
    )

    @Keep
    private external fun nativeSetSpace(
        handle: Long,
        space: Int,
    )

    @Keep
    private external fun nativeSetProtection(
        handle: Long,
        enabled: Boolean,
    )

    @Keep
    private external fun nativeSetCustomProfile(
        handle: Long,
        primary: Float,
        ratio: Float,
        dual: Boolean,
        richness: Float,
        attackMs: Float,
        releaseMs: Float,
        saturation: Float,
    )

    @Keep
    private external fun nativeReadTelemetry(
        handle: Long,
        destination: FloatArray,
    ): Int

    @Keep
    private external fun nativeReadWaveform(
        handle: Long,
        destination: FloatArray,
    ): Int
}
