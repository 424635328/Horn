package com.industrial.horn.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observes media volume and output-route changes. When headphones are present above 70% the app
 * asks the engine for -6 dB protection; this class only owns the policy and system plumbing.
 */
class AndroidAudioSafetyMonitor(
    context: Context,
) : AudioSafetyMonitor {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(currentState())
    override val state: StateFlow<AudioSafetyState> = _state.asStateFlow()

    private val volumeObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refresh()
            }
        }

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver,
            )
        }
        runCatching {
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
        refresh()
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { appContext.contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
    }

    override fun setVolumeTo60Percent() {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = AudioSafetyPolicy.sixtyPercentVolume(max)
        runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        refresh()
    }

    override fun refresh() {
        _state.value = currentState()
    }

    private fun currentState(): AudioSafetyState {
        val manager = audioManager
        if (manager == null) {
            return AudioSafetyPolicy.evaluate(0, 1, false)
        }
        val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val headphones = hasHeadphones(manager)
        return AudioSafetyPolicy.evaluate(volume, max, headphones)
    }

    private fun hasHeadphones(manager: AudioManager): Boolean {
        val devices =
            runCatching {
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            }.getOrDefault(emptyArray())
        return devices.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                -> true

                else -> false
            }
        }
    }
}
