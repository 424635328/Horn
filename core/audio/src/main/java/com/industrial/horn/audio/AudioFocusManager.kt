package com.industrial.horn.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 焦点系统的窄接口：生产实现走 [AudioManager]，测试用桩实现做 request/abandon 收支断言。
 * 不把它做成 public API —— 只在 :core:audio 内部与单测之间使用。
 */
internal interface AudioFocusSystem {
    fun request(request: AudioFocusRequest): Int

    fun abandon(request: AudioFocusRequest)

    companion object {
        const val GRANTED = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        const val FAILED = AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }
}

private class AndroidAudioFocusSystem(
    context: Context,
) : AudioFocusSystem {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun request(request: AudioFocusRequest): Int =
        runCatching { audioManager?.requestAudioFocus(request) }.getOrNull()
            ?: AudioFocusSystem.FAILED

    override fun abandon(request: AudioFocusRequest) {
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }
}

/**
 * Owns a transient-gain [AudioFocusRequest] for the horn.
 *
 * 记账不变量（S10）：**每一次 ACK 过的 request 都有且只有一次 abandon**，包括系统主动收回
 * （LOSS / LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK）这条路径；否则 `dumpsys audio` 里会留下
 * 永远不释放的焦点持有者。持有关系用 [AtomicReference] + CAS 维护，request/abandon 都进同一
 * 临界区，因此回调线程与调用线程并发时不会双重释放，也不会漏释放。
 *
 * 焦点变化语义：
 * - LOSS / LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK → abandon + [onFocusLost]（session 立即静音）
 * - GAIN → [onFocusGained]（调用方可以据此恢复界面上的「可鸣笛」提示）
 */
class AudioFocusManager
    internal constructor(
        private val system: AudioFocusSystem,
        private val onFocusLost: (Int) -> Unit,
        private val onFocusGained: () -> Unit,
    ) {
        constructor(
            context: Context,
            onFocusLost: (Int) -> Unit,
            onFocusGained: () -> Unit = {},
        ) : this(AndroidAudioFocusSystem(context), onFocusLost, onFocusGained)

        private val ledger = FocusLedger<AudioFocusRequest>()

        private val listener =
            AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> onFocusGained()
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        // 系统收回焦点时我们也必须 abandon 一次，保持 request/abandon 1:1。
                        ledger.release(system::abandon)
                        onFocusLost(change)
                    }
                }
            }

        fun requestFocus(): Boolean =
            ledger.acquire(
                token =
                    AudioFocusRequest
                        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build(),
                        ).setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(listener)
                        .build(),
                request = system::request,
                granted = AudioFocusSystem.GRANTED,
            )

        fun abandonFocus() {
            ledger.release(system::abandon)
        }
    }

/**
 * 焦点记账（S10）：只做「一次成功 request 对应一次 abandon」的 CAS 转账，不接触任何 Android
 * 对象，因此可以纯 JVM 断言收支平衡；[AudioFocusManager] 只负责把 Android 的 request/abandon
 * 注入进来。
 */
internal class FocusLedger<T : Any> {
    private val lock = Any()
    private val held = java.util.concurrent.atomic.AtomicReference<T?>(null)

    val isHeld: Boolean
        get() = held.get() != null

    /**
     * 已持有时直接返回 true（不重复 request）；否则请求一次，仅在结果为 [granted] 时入账。
     */
    fun acquire(
        token: T,
        request: (T) -> Int,
        granted: Int,
    ): Boolean =
        synchronized(lock) {
            if (held.get() != null) return true
            val result = request(token)
            if (result == granted) {
                held.set(token)
                true
            } else {
                false
            }
        }

    /** CAS 出账并 abandon；重复调用、与回调并发时最多只有一个成功者。 */
    fun release(abandon: (T) -> Unit): Boolean =
        synchronized(lock) {
            val token = held.get() ?: return false
            if (!held.compareAndSet(token, null)) return false
            abandon(token)
            true
        }
}