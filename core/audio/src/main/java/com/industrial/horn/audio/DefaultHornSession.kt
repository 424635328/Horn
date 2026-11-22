package com.industrial.horn.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production session coordinator.
 *
 * Safety invariant: any event that can make the app lose foreground control (audio-focus loss,
 * noisy route change, engine failure, lifecycle pause) funnels into [panicSilently], which
 * pauses AudioTrack, abandons focus and cancels vibration atomically. There is no code path that
 * leaves the horn sounding while the session is not active.
 */
internal class DefaultHornSession(
    context: Context,
    private val engine: HornAudioEngine,
    private val haptics: HornHapticFeedback,
) : HornSession {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val released = AtomicBoolean(false)

    private val _events =
        MutableSharedFlow<HornSessionEvent>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events = _events.asSharedFlow()

    private var active = false

    private val focusManager =
        AudioFocusManager(appContext) {
            interrupt(InterruptionReason.AUDIO_FOCUS_LOST)
        }

    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    interrupt(InterruptionReason.AUDIO_BECOMING_NOISY)
                }
            }
        }

    init {
        try {
            ContextCompat.registerReceiver(
                appContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: RuntimeException) {
            // Focus and lifecycle safety remain active even if the receiver cannot be registered.
        }

        scope.launch { engine.prepare() }
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is HornAudioEvent.Failure -> onEngineFailure(event.cause)
                }
            }
        }
    }

    override fun start(type: HornType): HornStartResult =
        synchronized(stateLock) {
            if (released.get()) return HornStartResult.ENGINE_UNAVAILABLE
            if (active) return HornStartResult.GRANTED

            if (!focusManager.requestFocus()) {
                return HornStartResult.FOCUS_DENIED
            }

            val started =
                try {
                    engine.selectHorn(type)
                    engine.start()
                } catch (_: RuntimeException) {
                    false
                }

            if (!started) {
                focusManager.abandonFocus()
                return HornStartResult.ENGINE_UNAVAILABLE
            }

            active = true
            haptics.startHonk(type)
            HornStartResult.GRANTED
        }

    override fun setExpression(expression: Float) {
        engine.setExpression(expression)
    }

    override fun setAcousticSpace(space: AcousticSpace) {
        engine.setAcousticSpace(space)
    }

    override fun setProtection(enabled: Boolean) {
        engine.setProtection(enabled)
    }

    override fun setCustomProfile(profile: CustomHornProfile) {
        engine.setCustomProfile(profile)
    }

    override fun setOutputMode(mode: OutputMode) {
        engine.setOutputMode(mode)
    }

    override fun telemetrySnapshot(): AudioTelemetry = engine.telemetrySnapshot()

    override fun readWaveform(destination: FloatArray): Int = engine.readWaveform(destination)

    override fun readWaveformStereo(
        left: FloatArray,
        right: FloatArray,
    ): Int = engine.readWaveformStereo(left, right)

    override fun stop() {
        synchronized(stateLock) {
            if (released.get()) return
            active = false
            engine.stop()
            focusManager.abandonFocus()
            haptics.stop()
        }
    }

    override fun panicStop() {
        synchronized(stateLock) {
            panicSilently()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return

        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        synchronized(stateLock) {
            active = false
            engine.panicStop()
            focusManager.abandonFocus()
            haptics.stop()
        }
        runCatching { engine.release() }
        runCatching { haptics.release() }
        scope.cancel()
    }

    private fun interrupt(reason: InterruptionReason) {
        synchronized(stateLock) {
            if (released.get()) return
            val wasActive = active
            panicSilently()
            if (wasActive) {
                _events.tryEmit(HornSessionEvent.Interrupted(reason))
            }
        }
    }

    private fun onEngineFailure(cause: Throwable) {
        synchronized(stateLock) {
            if (released.get()) return
            val wasActive = active
            panicSilently()
            if (wasActive) {
                _events.tryEmit(HornSessionEvent.EngineFailed(cause))
            }
        }
    }

    private fun panicSilently() {
        active = false
        engine.panicStop()
        focusManager.abandonFocus()
        haptics.stop()
    }
}
