package com.industrial.horn.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Prefers the native Oboe engine. If AAudio cannot open a stream, the same press is handled by
 * the zero-GC AudioTrack engine. Every parameter is mirrored to both backends so a later fallback
 * starts with the same type, space, expression and protection state.
 */
class FallbackHornAudioEngine(
    private val primary: HornAudioEngine?,
    private val fallback: HornAudioEngine,
) : HornAudioEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events =
        MutableSharedFlow<HornAudioEvent>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: Flow<HornAudioEvent> = _events.asSharedFlow()

    @Volatile
    private var active: HornAudioEngine? = null

    @Volatile
    private var fallbackLocked = false

    init {
        primary?.let { engine ->
            scope.launch {
                engine.events.collect { event ->
                    fallbackLocked = true
                    _events.tryEmit(event)
                }
            }
        }
        scope.launch {
            fallback.events.collect { event -> _events.tryEmit(event) }
        }
    }

    override fun prepare() {
        primary?.prepare()
        fallback.prepare()
    }

    override fun selectHorn(type: HornType) {
        primary?.selectHorn(type)
        fallback.selectHorn(type)
    }

    override fun setExpression(expression: Float) {
        primary?.setExpression(expression)
        fallback.setExpression(expression)
    }

    override fun setAcousticSpace(space: AcousticSpace) {
        primary?.setAcousticSpace(space)
        fallback.setAcousticSpace(space)
    }

    override fun setProtection(enabled: Boolean) {
        primary?.setProtection(enabled)
        fallback.setProtection(enabled)
    }

    override fun setCustomProfile(profile: CustomHornProfile) {
        primary?.setCustomProfile(profile)
        fallback.setCustomProfile(profile)
    }

    override fun telemetrySnapshot(): AudioTelemetry = (active ?: if (fallbackLocked) fallback else primary ?: fallback).telemetrySnapshot()

    override fun readWaveform(destination: FloatArray): Int =
        (active ?: if (fallbackLocked) fallback else primary ?: fallback).readWaveform(destination)

    override fun start(): Boolean {
        if (!fallbackLocked) {
            val native = primary
            if (native != null) {
                val started = runCatching { native.start() }.getOrDefault(false)
                if (started) {
                    active = native
                    return true
                }
                fallbackLocked = true
                runCatching { native.release() }
            }
        }

        val chosen = fallback
        val started = runCatching { chosen.start() }.getOrDefault(false)
        if (started) active = chosen
        return started
    }

    override fun stop() {
        primary?.stop()
        fallback.stop()
    }

    override fun panicStop() {
        primary?.panicStop()
        fallback.panicStop()
    }

    override fun release() {
        runCatching { primary?.release() }
        runCatching { fallback.release() }
        scope.cancel()
    }
}

object HornAudioEngineFactory {
    fun create(): HornAudioEngine {
        val native =
            if (OboeHornAudioEngine.isSupported()) {
                runCatching { OboeHornAudioEngine() }.getOrNull()
            } else {
                null
            }
        val fallback = SynthAudioTrackEngine()
        return if (native == null) fallback else FallbackHornAudioEngine(native, fallback)
    }
}
