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
import kotlinx.coroutines.delay
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
        AudioFocusManager(
            context = appContext,
            onFocusLost = { interrupt(InterruptionReason.AUDIO_FOCUS_LOST) },
            onFocusGained = {
                // 焦点回来后门控状态由 UI 自行刷新；session 不在这里重启音频（安全侧只做静音）。
            },
        )

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

    /**
     * 平滑收尾（S8）：[HornAudioEngine.stop] 只是请求释放包络，真正静音要等渲染线程吐完尾音。
     * 因此焦点交还延后到「引擎报告不再发声」或 [TAIL_RELEASE_MS] 上限之后，避免焦点刚交还给
     * 上一个应用就被我们的尾音盖住；[panicStop] 仍然是立即静音 + 立即交还。
     *
     * 尾音等待发生在 session 自己的协程里，绝不持锁睡眠，也不阻塞调用线程。
     */
    override fun stop() {
        val wasActive =
            synchronized(stateLock) {
                if (released.get()) {
                    false
                } else {
                    active = false
                    engine.stop()
                    haptics.stop()
                    true
                }
            }
        if (!wasActive) return
        scope.launch {
            var waited = 0L
            while (waited < TAIL_RELEASE_MS && engine.telemetrySnapshot().isPlaying) {
                delay(TAIL_POLL_MS)
                waited += TAIL_POLL_MS
            }
            synchronized(stateLock) {
                // 尾音期间又重新开始鸣笛时不能交还焦点。
                if (!active && !released.get()) focusManager.abandonFocus()
            }
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

    private companion object {
        /** 尾音交还焦点的上限：超过它就不再等（硬上限，不是目标值）。 */
        const val TAIL_RELEASE_MS = 60L

        /** 尾音轮询周期：只读一次 telemetry 快照，不碰渲染线程。 */
        const val TAIL_POLL_MS = 4L
    }

    private fun panicSilently() {
        active = false
        engine.panicStop()
        focusManager.abandonFocus()
        haptics.stop()
    }
}
